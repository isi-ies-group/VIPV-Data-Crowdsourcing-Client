package es.upm.ies.surco

import android.app.*
import android.content.ComponentCallbacks2
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
import es.upm.ies.surco.ui.ActMain
import es.upm.ies.surco.workers.SessionFilesUploadWorker
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.Identifier
import org.apache.commons.collections4.queue.CircularFifoQueue
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import kotlin.concurrent.thread


class AppMain : Application(), ComponentCallbacks2, SensorEventListener {
    // API & user services
    private lateinit var apiService: APIService
    lateinit var apiUserSession: ApiUserSession

    val hasCompassSensor: Boolean by lazy {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
    }

    // Beacons abstractions
    var loggingSession = LoggingSession

    // Ring buffer to store the last 5 numbers of beacons detected,
    // to avoid reporting a lower number by mis-skipping them in scans
    private val nRangedBeaconsBuffer = CircularFifoQueue<Int>(5)

    // -- for public use by UI --
    val nRangedBeacons: MutableLiveData<Int> = MutableLiveData(0)
    val wasUploadedSuccessfully = MutableLiveData<Boolean>(false)

    // Data for the beacon session
    val sessionRunning = MutableLiveData<Boolean>(false)
    val isSessionActive: LiveData<Boolean> get() = sessionRunning

    // Status update handler
    // This handler is used to update the status of the beacons every STATUS_UPDATE_INTERVAL milliseconds
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            loggingSession.beacons.value?.forEach { beacon ->
                beacon.refreshStatus()
            }
            handler.postDelayed(this, STATUS_UPDATE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Session initialization
        loggingSession.init(cacheDir)

        // Set the theme
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = sharedPreferences.getString("color_theme", "system-default") ?: "system-default"
        setupTheme(theme)

        // Initialize the Bluetooth global scanner state
        val beaconManager = BeaconManager.getInstanceForApplication(this)
        // By default, the library will detect AltBeacon protocol beacons
        beaconManager.beaconParsers.clear()
        // m:0-1=0505 stands for InPlay's Company Identifier Code (0x0505),
        // see https://www.bluetooth.com/specifications/assigned-numbers/
        // i:2-7 stands for the identifier, UUID (MAC) [little endian]
        // d:8-9 stands for the data, CH1 analog value [little endian]
        val customParser = BeaconParser().setBeaconLayout("m:0-1=0505,i:2-7,d:8-9")
        beaconManager.beaconParsers.add(customParser)

        // Activate debug mode only if build variant is debug
        @Suppress("SENSELESS_COMPARISON")
        if (BuildConfig.DEBUG) {
            BeaconManager.setDebug(true)
        }

        // Set API service
        setupApiService()

        // Save instance for singleton access
        instance = this
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
        beacons: Collection<Beacon>, timestamp: Instant
    ) {
        for (beacon in beacons) {
            val id = beacon.id1
            val data = beacon.dataFields
            // analogReading is the CH1 analog value, as two bytes in little endian
            val analogReading = data[0].toShort()
            addSensorDataEntry(timestamp, id, analogReading)
        }

        // Update the number of beacons detected
        nRangedBeaconsBuffer.add(beacons.size)
        nRangedBeacons.value = nRangedBeaconsBuffer.maxOrNull() ?: 0
    }

    /**
     * Add sensor data entry to the loggingSession
     * @param timestamp: Instant, timestamp of the data
     * @param id: Identifier, identifier of the beacon
     * @param data: Short, data to be added to the beacon
     */
    fun addSensorDataEntry(timestamp: Instant, id: Identifier, data: Short) {
        loggingSession.addBLESensorEntry(timestamp, id, data)
    }

    /**
     * Add GPS and compass data to the loggingSession
     * Gets called with a timestamp and the GPS location
     * Calculates the compass bearing angle.
     */
    fun addLocationDataEntry(timestamp: Instant, latitude: Float, longitude: Float) {
        if (hasCompassSensor) {
            val compassAngle = getCompassAzimuth()
            loggingSession.addGpsAndCompassInfo(timestamp, latitude.toDouble(), longitude.toDouble(), compassAngle)
        } else {
            loggingSession.addGpsAndCompassInfo(timestamp, latitude.toDouble(), longitude.toDouble(), Float.NaN)
        }
    }

    /**
     * Get compass azimuth angle in degrees.
     */
    private val sensorManager: SensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }
    private val accelerometer: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    private val magnetometer: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }
    private val magnetometerAccuracyStatus = MutableLiveData<Int>(SensorManager.SENSOR_STATUS_UNRELIABLE)
    private val accelerometerAccuracyStatus = MutableLiveData<Int>(SensorManager.SENSOR_STATUS_UNRELIABLE)
    private val minSensorAccuracy = MutableLiveData<Int>(SensorManager.SENSOR_STATUS_UNRELIABLE)
    val sensorAccuracy: LiveData<Int> get() = minSensorAccuracy
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccelerometerReading = FloatArray(3)
    private val lastMagnetometerReading = FloatArray(3)
    fun getCompassAzimuth(): Float {
        // get last reading of accelerometer and magnetometer
        SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometerReading, lastMagnetometerReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val azimuthInRadians = orientationAngles[0]
        return Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Update the last accelerometer reading
                System.arraycopy(event.values, 0, lastAccelerometerReading, 0, lastAccelerometerReading.size)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Update the last magnetometer reading
                System.arraycopy(event.values, 0, lastMagnetometerReading, 0, lastMagnetometerReading.size)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerAccuracyStatus.postValue(accuracy)
        } else if (sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerAccuracyStatus.postValue(accuracy)
        }
        minSensorAccuracy.postValue(
            minOf(
                magnetometerAccuracyStatus.value ?: SensorManager.SENSOR_STATUS_UNRELIABLE,
                accelerometerAccuracyStatus.value ?: SensorManager.SENSOR_STATUS_UNRELIABLE
            )
        )
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
            // Register listeners for sensors
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
            // Set the status of the session to running
            sessionRunning.postValue(true)
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
        // Create a coroutine to write the session data to a file
        thread {
            val serviceIntent = Intent(this, ForegroundBeaconScanService::class.java)
            stopService(serviceIntent)
            loggingSession.concludeSession()
        }
        // Set the status of the session to not running
        sessionRunning.postValue(false)
        // Unregister listeners to save battery
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(statusUpdateRunnable) // Stop periodic status updates
    }

    /**
     * Toggle the beacon scanning session from opened UI button. Shows a toast message.
     * @return void
     *
     * Observe sessionRunning LiveData to get the current state of the session
     */
    fun toggleSession() {
        if (sessionRunning.value == true) {
            concludeSession()
            Toast.makeText(
                this, getString(R.string.session_stopped), Toast.LENGTH_SHORT
            ).show()
        } else {
            startBeaconScanning()
            Toast.makeText(
                this, getString(R.string.session_started), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Public facing start monitoring and ranging for beacons
     */
    fun startSession() {
        if (sessionRunning.value != true) {
            startBeaconScanning()
        }
    }

    /**
     * Public facing conclude the session and save it to a file
     */
    fun concludeSession() {
        stopBeaconScanning()
        // if the sharedPreference is set to upload files on metered network, schedule the upload
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUploadBehaviour =
            sharedPreferences.getString("auto_upload_behaviour", "auto_un_metered")
        when (autoUploadBehaviour) {
            "auto_un_metered" -> scheduleFileUpload()
            "auto_always" -> uploadAll()
            // else, manual upload -> do nothing
        }
    }

    fun scheduleFileUpload() {
        Log.i(TAG, "Scheduling file upload")
        // Define constraints for unmetered network
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        // Create a OneTimeWorkRequest
        val fileUploadWorkRequest =
            OneTimeWorkRequestBuilder<SessionFilesUploadWorker>().setConstraints(constraints)
                .build()

        // Enqueue the work
        WorkManager.getInstance(this).enqueue(fileUploadWorkRequest)
    }

    fun uploadAll() {
        // Create a OneTimeWorkRequest
        val fileUploadWorkRequest = OneTimeWorkRequestBuilder<SessionFilesUploadWorker>().build()
        // Enqueue the work
        WorkManager.getInstance(this).enqueue(fileUploadWorkRequest)
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
        lateinit var instance: AppMain
            private set  // This is a singleton, setter is private but access is public
        const val TAG = "AppMain"
        const val NOTIFICATION_ONGOING_SESSION_ID = 1
        const val NOTIFICATION_NO_LOCATION_OR_BLUETOOTH_ID = 2
        const val ACTION_STOP_SESSION = "es.upm.ies.surco.STOP_SESSION"
        const val GPS_LOCATION_PERIOD_MILLIS = 1000L  // 1 second
        const val STATUS_UPDATE_INTERVAL = 3000L  // 3 seconds
    }  // companion object
}
