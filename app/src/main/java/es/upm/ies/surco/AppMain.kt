package es.upm.ies.surco

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import es.upm.ies.surco.api.APIService
import es.upm.ies.surco.api.ApiUserSession
import es.upm.ies.surco.service.ForegroundBeaconScanService
import es.upm.ies.surco.session_logging.LoggingSession
import es.upm.ies.surco.session_logging.LoggingSessionStatus
import es.upm.ies.surco.ui.ActMain
import es.upm.ies.surco.workers.SessionFilesUploadWorker
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import kotlin.concurrent.thread


class AppMain : Application(), ComponentCallbacks2 {
    // API & user services
    private lateinit var apiService: APIService
    lateinit var apiUserSession: ApiUserSession

    // Beacons abstractions
    var loggingSession = LoggingSession
    lateinit var beaconManager: BeaconManager

    // -- for public use by UI --
    val wasUploadedSuccessfully = MutableLiveData<Boolean>(false)  // set by the worker

    val minSensorAccuracy = MutableLiveData<Int>(SensorManager.SENSOR_STATUS_UNRELIABLE)
    val sensorAccuracyValue: LiveData<Int> get() = minSensorAccuracy

    // Data for the beacon session
    var scanInterval: Long = DEFAULT_SCAN_WINDOW_INTERVAL

    // Status update handler
    // This handler is used to update the status of the beacons every STATUS_UPDATE_INTERVAL milliseconds
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            loggingSession.refreshBeaconStatuses()
            handler.postDelayed(this, STATUS_UPDATE_INTERVAL)
        }
    }

    val cachedSessionsDir by lazy {
        cacheDir.resolve(SESSIONS_SUBDIR_IN_CACHE)
    }

    override fun onCreate() {
        super.onCreate()

        // Assign the singleton instance
        instance = this

        // Create the cached sessions directory if it does not exist
        if (!cachedSessionsDir.exists()) {
            cachedSessionsDir.mkdirs()
        }
        // Session initialization
        loggingSession.init(cachedSessionsDir)

        // Initialize the shared preferences
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // Set the theme
        val theme = sharedPreferences.getString("color_theme", "system-default") ?: "system-default"
        setupTheme(theme)
        // Load the scan interval
        try {
            scanInterval = sharedPreferences.getString("scan_interval", null)?.toLong()
                ?: DEFAULT_SCAN_WINDOW_INTERVAL
        } catch (_: NumberFormatException) {
            Log.e(TAG, "Invalid scan interval")
            scanInterval = DEFAULT_SCAN_WINDOW_INTERVAL
        }

        beaconManager = BeaconManager.getInstanceForApplication(this)
        // By default, the library will detect AltBeacon protocol beacons
        beaconManager.beaconParsers.clear()
        // m:0-1=0505 stands for InPlay's Company Identifier Code (0x0505),
        // see https://www.bluetooth.com/specifications/assigned-numbers/
        // i:2-7 stands for the identifier, UUID (MAC) [little endian]
        // d:8-9 stands for the data, CH1 analog value [little endian]
        val customParser = BeaconParser().setBeaconLayout("m:0-1=0505,i:2-7,d:8-9")
        beaconManager.beaconParsers.add(customParser)

        // Activate debug mode only if build variant is debug
        BeaconManager.setDebug(BuildConfig.DEBUG)

        // Set API service
        setupApiService()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        loggingSession.freeDataTemporarily()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_BACKGROUND) {
            Log.d(TAG, "Trimming memory in background")
            loggingSession.freeDataTemporarily()
        }
    }

    override fun onTerminate() {
        stopBeaconScanning()
        super.onTerminate()
    }

    /**
     * Add sensor data entries to the loggingSession from the beacon data returned by the ranging
     * @param beacons: Collection<Beacon>, list of beacons detected by the ranging
     * @param timestamp: Instant, timestamp of the data
     */
    fun addBeaconCollectionData(
        beacons: Collection<Beacon>,
        timestamp: Instant,
        latitude: Float,
        longitude: Float,
        azimuth: Float
    ) {
        for (beacon in beacons) {
            val id = beacon.id1
            val data = beacon.dataFields
            // analogReading is the CH1 analog value, as two bytes in little endian
            val analogReading = data[0].toShort()
            addSensorDataEntry(timestamp, id, analogReading, latitude, longitude, azimuth)
        }
    }

    /**
     * Add sensor data entry to the loggingSession
     * @param timestamp: Instant, timestamp of the data
     * @param id: Identifier, identifier of the beacon
     * @param data: Short, data to be added to the beacon
     *
     */
    fun addSensorDataEntry(
        timestamp: Instant,
        id: Identifier,
        data: Short,
        latitude: Float,
        longitude: Float,
        azimuth: Float
    ) {
        loggingSession.addBLESensorEntry(timestamp, id, data, latitude, longitude, azimuth)
    }

    /**
     * Setup the API service endpoint (callback for configuration changes)
     * If the endpoint is not set in PreferenceManager.getDefaultSharedPreferences,
     * the default value is used (from BuildConfig)
     * @param newUri: String, new URI to set for the API service if known, else it is read from shared preferences
     * @return void
     */
    fun setupApiService(newUri: String? = null) {
        if (newUri.isNullOrBlank()) {
            // Get the API URI setting
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            var endpoint = sharedPreferences.getString("api_uri", BuildConfig.SERVER_URL)
                ?: BuildConfig.SERVER_URL
            if (endpoint.isBlank()) {
                endpoint = BuildConfig.SERVER_URL
            }
            setupApiService(endpoint)
            return
        }
        var endpoint = newUri
        if (!endpoint.endsWith("/")) {
            endpoint += "/"
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "http://${endpoint}"
        }
        val retrofit =
            Retrofit.Builder().baseUrl(endpoint).addConverterFactory(GsonConverterFactory.create())
                .build()
        this.apiService = retrofit.create(APIService::class.java)
        // Load user session from shared preferences
        apiUserSession =
            ApiUserSession(PreferenceManager.getDefaultSharedPreferences(this), apiService)
    }

    /**
     * Test the API endpoint by sending a GET request to the server
     */
    suspend fun testApiEndpoint(): Boolean {
        try {
            val response = apiService.isUp()
            Log.i(TAG, "API is up: $response")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "API is down: $e")
            return false
        }
    }

    fun isForegroundBeaconScanServiceRunning() =
        isServiceRunning(ForegroundBeaconScanService::class.java)

    /**
     * Start monitoring and ranging for beacons
     * @return void
     */
    private fun startBeaconScanning() {
        if (isForegroundBeaconScanServiceRunning()) {
            Log.i(TAG, "Beacon scan service is already running")
            return
        } else {
            Log.i(TAG, "Starting beacon scan service")
            // Start the beacon scanning service
            thread {
                loggingSession.startSession()

                val serviceIntent = Intent(this, ForegroundBeaconScanService::class.java)
                startService(serviceIntent)
            }
            handler.post(statusUpdateRunnable) // Start periodic status updates of the beacon statuses
        }
    }

    /**
     * Stop monitoring and ranging for beacons
     * @return void
     */
    fun stopBeaconScanning() {
        thread {
            val serviceIntent = Intent(this, ForegroundBeaconScanService::class.java)
            stopService(serviceIntent)
        }
        // Stop periodic status updates
        handler.removeCallbacks(statusUpdateRunnable)
    }

    /**
     * Toggle the beacon scanning session from opened UI button. Shows a toast message.
     * @return void
     *
     * Observe sessionRunning LiveData to get the current state of the session
     */
    fun toggleSession() {
        if (loggingSession.status.value == LoggingSessionStatus.SESSION_ONGOING) {
            concludeSession()
        } else if (loggingSession.status.value == LoggingSessionStatus.SESSION_TRIGGERABLE) {
            startBeaconScanning()
        }
    }

    /**
     * Public facing conclude the session and save it to a file
     */
    fun concludeSession() {
        thread {
            stopBeaconScanning()
            val file = loggingSession.concludeSession()
            if (file != null) {  // null means it was not valid session
                // if the sharedPreference is set to upload files on metered network, schedule the upload
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                val autoUploadBehaviour =
                    sharedPreferences.getString("auto_upload_behaviour", "auto_un_metered")
                when (autoUploadBehaviour) {
                    "auto_un_metered" -> scheduleFileUpload(now = false)
                    "auto_always" -> scheduleFileUpload(now = true)
                    // else, manual upload -> do nothing
                }
            }
        }
    }

    /**
     * Schedule a file upload to the server
     * @param now: Boolean, true if the upload should be done immediately, false otherwise
     * @return void
     */
    fun scheduleFileUpload(now: Boolean) {
        Log.i(TAG, "Scheduling file upload ${if (now) "now" else "later"}")
        // Create a OneTimeWorkRequest
        val fileUploadWorkRequestBuilder = OneTimeWorkRequestBuilder<SessionFilesUploadWorker>()

        if (now) {
            // Define constraints for non-metered networks
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            fileUploadWorkRequestBuilder.setConstraints(constraints)
        }

        val fileUploadWorkRequest = fileUploadWorkRequestBuilder.build()

        // Enqueue the work
        WorkManager.getInstance(this).enqueue(fileUploadWorkRequest)
    }

    fun uploadAllSessions() {
        // Create a OneTimeWorkRequest
        val fileUploadWorkRequest = OneTimeWorkRequestBuilder<SessionFilesUploadWorker>().build()
        // Enqueue the work
        WorkManager.getInstance(this).enqueue(fileUploadWorkRequest)
    }

    fun deleteAllSessions(callback: (Boolean) -> Unit) {
        thread {
            val files = loggingSession.getSessionFiles()
            var success = true
            files.forEach { file ->
                if (file.exists() && !file.delete()) {
                    success = false
                }
            }
            Handler(Looper.getMainLooper()).post {
                callback(success)
            }
        }
    }

    /**
     * Check if a service is running.
     * @param serviceClass: Class<*>, the class of the service to check
     * @return Boolean, true if the service is running, false otherwise
     *
     * This function is used to check if the ForegroundBeaconScanService is running.
     *
     * Notes:
     * 1. getRunningServices() is deprecated in newer Android versions (API level 21 and above),
     *    and it might not provide full reliability for services running in the background.
     * 2. Starting from Android 5.0 (API level 21), apps can no longer directly access information
     *    about other apps' services due to increased restrictions on background processes.
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager

        @Suppress("DEPRECATION") val runningServices = manager.getRunningServices(Int.MAX_VALUE)

        for (service in runningServices) {
            if (service.service.className == serviceClass.name) {
                return true
            }
        }
        return false
    }

    /**
     * Setup the application theme (callback for configuration changes)
     */
    fun setupTheme(theme: String) {
        Log.i(TAG, "Setting theme to $theme")
        when (theme) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> {  // default to system theme, may be default key "system-default"
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        // Restart the activity to apply the new theme
        val intent = Intent(this, ActMain::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    companion object {
        private var instance: AppMain? = null

        fun getInstance(): AppMain {
            return instance ?: throw IllegalStateException("AppMain not initialized")
        }

        const val TAG = "AppMain"
        const val SESSIONS_SUBDIR_IN_CACHE = "sessions"
        const val NOTIFICATION_ONGOING_SESSION_ID = 1
        const val NOTIFICATION_NO_LOCATION_OR_BLUETOOTH_ID = 2
        const val ACTION_STOP_SESSION = "es.upm.ies.surco.STOP_SESSION"
        const val GPS_LOCATION_PERIOD_MILLIS = 1000L  // 1 second
        const val STATUS_UPDATE_INTERVAL = 2000L  // 2 seconds
        const val DEFAULT_SCAN_WINDOW_INTERVAL = 50L  // 50 milliseconds
    }  // companion object
}
