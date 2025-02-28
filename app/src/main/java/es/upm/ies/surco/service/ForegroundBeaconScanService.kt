package es.upm.ies.surco.service

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.AppMain.Companion.ACTION_STOP_SESSION
import es.upm.ies.surco.broadcastReceivers.StopBroadcastReceiver
import es.upm.ies.surco.ui.ActMain
import es.upm.ies.surco.R
import com.google.android.gms.location.*
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.Region
import org.altbeacon.beacon.service.BeaconService
import org.altbeacon.beacon.service.Callback
import java.time.Instant
import kotlin.concurrent.thread

class ForegroundBeaconScanService : BeaconService(), SensorEventListener {
    private lateinit var appMain: AppMain

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var locationManager: LocationManager

    private val beaconManager: BeaconManager by lazy {
        BeaconManager.getInstanceForApplication(this)
    }
    val region = Region(
        "all-beacons", null, null, null
    )  // criteria for identifying beacons

    val rangingCallback: Callback = object : Callback("es.upm.ies.surco") {
        override fun call(context: Context?, dataName: String?, data: Bundle?): Boolean {
            val timestamp = Instant.now()
            // get the beacons from the bundle
            // if the SDK version is 32 or higher, use the new method to get the beacons
            // as the old method is deprecated as type-unsafe
            val beacons = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableArrayList("beacons", Beacon::class.java) ?: return false
            } else {
                @Suppress("DEPRECATION")  // Deprecated in API 32
                data?.getParcelableArrayList<Beacon>("beacons") ?: return false
            }
            // add the beacons to the AppMain singleton
            appMain.addBeaconCollectionData(beacons, timestamp, latitude, longitude, getCompassAzimuth())
            return true
        }
    }
    val monitoringCallback: Callback = object : Callback("es.upm.ies.surco") {
        override fun call(context: Context?, dataName: String?, data: Bundle?): Boolean {
            Log.i(TAG, "Monitoring callback called with dataName: $dataName")
            return true
        }
    }

    /**
     * Latitude and longitude of the user's location.
     */
    private var latitude: Float = Float.NaN
    private var longitude: Float = Float.NaN

    /**
     * Flag to indicate if the watchdog thread is running.
     */
    @Volatile
    private var watchdogMayRun = false

    /**
     * Watchdog thread that continuously checks if Bluetooth and GPS are enabled.
     */
    private val watchdogThread = thread(start = false) {
        while (watchdogMayRun) {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isBluetoothEnabled = bluetoothManager.adapter.isEnabled

            handleConnectivityNotification(isGpsEnabled, isBluetoothEnabled)

            try {
                Thread.sleep(2500)
            } catch (_: InterruptedException) {
                break  // Gracefully exit the thread if interrupted, we already have enough drama in this world
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Service created")

        // get AppMain singleton
        appMain = AppMain.instance

        // Get the Bluetooth and Location managers
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Initialize the location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Configure the location request
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, AppMain.GPS_LOCATION_PERIOD_MILLIS
        ).setMinUpdateIntervalMillis(AppMain.GPS_LOCATION_PERIOD_MILLIS)
            .setWaitForAccurateLocation(true).build()

        // Setup location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // save latitude and longitude for later use on data entries addition
                latitude = locationResult.lastLocation?.latitude?.toFloat() ?: Float.NaN
                longitude = locationResult.lastLocation?.longitude?.toFloat() ?: Float.NaN
            }
        }

        // Create all notification channels
        createNotificationChannels()

        // Register listeners for sensors
        startSensorUpdates()

        // Start monitoring Bluetooth and GPS connectivity
        startForegroundServiceWithNotification()
        startLocationUpdates()
        startBluetoothAndGpsWatchdog()

        // Start ranging and monitoring beacons
        startRangingBeaconsInRegion(region, rangingCallback)
        startMonitoringBeaconsInRegion(region, monitoringCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorUpdates()
        stopBluetoothAndGpsWatchdog()
        stopLocationUpdates()
        // Stop ranging and monitoring beacons
        stopRangingBeaconsInRegion(region)
        stopMonitoringBeaconsInRegion(region)
        // Remove notifications
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(AppMain.NOTIFICATION_ONGOING_SESSION_ID)
        notificationManager.cancel(AppMain.NOTIFICATION_NO_LOCATION_OR_BLUETOOTH_ID)
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Create all notification channels used by the service.
     */
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Connectivity Service Channel
        val ongoingSessionChannel = NotificationChannel(
            "session-ongoing",  // id
            getString(R.string.notification_ongoing_channel_name),  // name
            NotificationManager.IMPORTANCE_HIGH,  // importance
        ).apply {
            description = getString(R.string.notification_ongoing_channel_description)
        }
        notificationManager.createNotificationChannel(ongoingSessionChannel)

        // Location and Bluetooth Watchdog Channel
        val watchdogChannel = NotificationChannel(
            "location-and-bluetooth-watchdog",  // id
            getString(R.string.notification_watchdog_channel_name),  // name
            NotificationManager.IMPORTANCE_HIGH  // importance
        ).apply {
            description = getString(R.string.notification_watchdog_channel_description)
        }
        notificationManager.createNotificationChannel(watchdogChannel)
    }

    /**
     * Start the service in the foreground with a notification.
     */
    private fun startForegroundServiceWithNotification() {
        // intents for stopping the session
        val stopIntent = Intent(this, StopBroadcastReceiver::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Create the notification
        val notification = NotificationCompat.Builder(this, "session-ongoing").apply {
            setContentTitle(getString(R.string.notification_ongoing_title))
            setContentText(getString(R.string.notification_ongoing_text))
            setSmallIcon(R.mipmap.logo_ies_foreground).setOngoing(true)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC).addAction(  // Stop action
                R.drawable.square_stop,
                getString(R.string.stop_notification_button),
                stopPendingIntent
            )
        }.setContentIntent(  // Explicit intent to open the app when notification is clicked
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ActMain::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

        // Start the service in the foreground
        beaconManager.enableForegroundServiceScanning(
            notification, AppMain.NOTIFICATION_ONGOING_SESSION_ID
        )
        beaconManager.setBackgroundBetweenScanPeriod(0)
        beaconManager.setBackgroundScanPeriod(1100)
        startForeground(AppMain.NOTIFICATION_ONGOING_SESSION_ID, notification)
    }

    /**
     * Start location updates. Allows to retrieve the user's location periodically.
     */
    private fun startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, null
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: $e")
        }
    }

    /**
     * Stop location updates.
     */
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    /**
     * Continuously check if Bluetooth and GPS are enabled.
     * If not, show a notification to the user.
     */
    private fun startBluetoothAndGpsWatchdog() {
        if (!watchdogMayRun) {
            watchdogMayRun = true
            watchdogThread.start()
        }
    }

    /**
     * Stop the watchdog thread.
     */
    private fun stopBluetoothAndGpsWatchdog() {
        watchdogMayRun = false
        watchdogThread.join() // Ensure the thread stops before continuing
    }

    private var lastNotificationState: Pair<Boolean, Boolean> = Pair(false, false)

    /**
     * Show a notification to the user if GPS or Bluetooth are disabled.
     * @param isGpsEnabled True if GPS is enabled, false otherwise.
     * @param isBluetoothEnabled True if Bluetooth is enabled, false otherwise.
     * @see startBluetoothAndGpsWatchdog
     */
    private fun handleConnectivityNotification(isGpsEnabled: Boolean, isBluetoothEnabled: Boolean) {
        val currentState = Pair(isGpsEnabled, isBluetoothEnabled)
        if (currentState == lastNotificationState) return  // No change -> do not create a new notification
        if (currentState == Pair(true, true)) {  // Both are enabled -> remove notification
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(AppMain.NOTIFICATION_NO_LOCATION_OR_BLUETOOTH_ID)
            return
        }

        // Create a notification
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationText = when {
            !isGpsEnabled && !isBluetoothEnabled -> getString(R.string.notification_no_location_bluetooth_text)
            !isGpsEnabled -> getString(R.string.notification_no_location_text)
            !isBluetoothEnabled -> getString(R.string.notification_no_bluetooth_text)
            else -> ""
        }

        val notification =
            NotificationCompat.Builder(this, "location-and-bluetooth-watchdog").apply {
                setContentTitle(getString(R.string.notification_no_location_bluetooth_title))
                setContentText(notificationText).setSmallIcon(R.mipmap.logo_ies_foreground)
                setOngoing(true)
            }.build()

        notificationManager.notify(AppMain.NOTIFICATION_NO_LOCATION_OR_BLUETOOTH_ID, notification)

        lastNotificationState = currentState
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
    private var magnetometerAccuracyStatus: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var accelerometerAccuracyStatus: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccelerometerReading = FloatArray(3)
    private val lastMagnetometerReading = FloatArray(3)
    fun getCompassAzimuth(): Float {
        if (!appMain.hasCompassSensor) return Float.NaN

        // get last reading of accelerometer and magnetometer
        SensorManager.getRotationMatrix(
            rotationMatrix, null, lastAccelerometerReading, lastMagnetometerReading
        )
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val azimuthInRadians = orientationAngles[0]
        return Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
    }

    /**
     * Called when the sensor values change.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Update the last accelerometer reading
                System.arraycopy(
                    event.values, 0, lastAccelerometerReading, 0, lastAccelerometerReading.size
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Update the last magnetometer reading
                System.arraycopy(
                    event.values, 0, lastMagnetometerReading, 0, lastMagnetometerReading.size
                )
            }
        }
    }

    /**
     * Called when the sensor accuracy changes.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magnetometerAccuracyStatus = accuracy
        } else if (sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            accelerometerAccuracyStatus = accuracy
        }
        appMain.minSensorAccuracy.postValue(
            minOf(
                magnetometerAccuracyStatus, accelerometerAccuracyStatus
            )
        )
    }

    /**
     * Start sensor updates.
     */
    fun startSensorUpdates() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /**
     * Stop sensor updates.
     */
    fun stopSensorUpdates() {
        sensorManager.unregisterListener(this)
    }

    companion object {
        private const val TAG = "ForegroundBeaconScanService"
    }
}
