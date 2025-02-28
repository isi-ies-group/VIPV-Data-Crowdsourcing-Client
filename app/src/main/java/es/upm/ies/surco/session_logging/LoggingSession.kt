package es.upm.ies.surco.session_logging

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import es.upm.ies.surco.notifyObservers
import org.altbeacon.beacon.Identifier
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Singleton object to hold the logging session and some other metadata.
 *
 * Handles the addition of SensorEntries to the beacons. Creates new instances of Beacon if the
 * identifier is not found in the list.
 *
 * The acquired data is categorized in three different types:
 *   - Time management: start and stop instants of the session and the timezone.
 *   - Android sensors data: GPS and compass data, with their timestamps.
 *   - BLE beacons data: data from the beacons, with their identifiers and the SensorEntries.
 *
 * Time management occupies the least amount of memory, as it is only a few variables.
 * However, the Android sensors data and the BLE beacons data can grow indefinitely,
 * so they may be stored in temporary files to free memory.
 */
object LoggingSession {
    /**
     * Session files prefix and suffix.
     */
    private const val SESSION_FILE_PREFIX = "VIPV_"
    private const val SESSION_FILE_EXTENSION = ".txt"

    /**
     * Temporary files prefix and suffix.
     */
    private const val TEMP_FILE_PREFIX = "dump_"
    private const val TEMP_FILE_EXTENSION = ".temp"

    /**
     * Time span of the session.
     */
    private var zone: TimeZone = TimeZone.getDefault()
    private var startZonedDateTime: ZonedDateTime? = null
    private var stopZonedDateTime: ZonedDateTime? = null

    /**
     * Android sensors data during the session.
     */
    private val gpsAndCompassData: MutableLiveData<ArrayList<GpsAndCompassInfo>> =
        MutableLiveData<ArrayList<GpsAndCompassInfo>>(ArrayList<GpsAndCompassInfo>())
    // val gpsAndCompass: LiveData<ArrayList<GpsAndCompassInfo>> = gpsAndCompassData  // unused (for now)
    private var gpsAndCompassDataCacheFile: File? = null

    /**
     * BLE beacons data during the session.
     */
    private val beaconsData: MutableLiveData<ArrayList<BeaconSimplified>> =
        MutableLiveData<ArrayList<BeaconSimplified>>(ArrayList<BeaconSimplified>())
    val beacons: LiveData<ArrayList<BeaconSimplified>> = beaconsData
    private var beaconsDataCacheFile: File? = null

    /**
     * Cache directory to store the session files.
     */
    private var cacheDir: File? = null

    /**
     * Initialize the singleton with the cache directory.
     */
    fun init(cacheDir: File) {
        this.cacheDir = cacheDir
    }

    /**
     * Adds a SensorEntry to the beacon with the given identifier. If the beacon is not found, it
     * creates a new instance of Beacon and adds it to the list.
     * @param timestamp The timestamp of the data.
     * @param id The identifier of the beacon.
     * @param data The data to be added to the beacon.
     */
    fun addBLESensorEntry(
        timestamp: Instant, id: Identifier, data: Short
    ) {
        val beacon = beaconsData.value?.find { it.id == id }
        if (beacon != null) {
            beacon.sensorData.value?.add(SensorEntry(data, timestamp))
            beacon.sensorData.notifyObservers()
            beaconsData.notifyObservers()
        } else {
            val newBeacon = BeaconSimplified(id)
            newBeacon.sensorData.value?.add(SensorEntry(data, timestamp))
            beaconsData.value!!.add(newBeacon)
            beaconsData.notifyObservers()
        }
    }

    /**
     * Adds a GPS and compass data to the session.
     * @param timestamp The timestamp of the data.
     * @param latitude The latitude of the data.
     * @param longitude The longitude of the data.
     * @param compassAngle The compass angle of the data.
     */
    fun addGpsAndCompassInfo(
        timestamp: Instant, latitude: Double, longitude: Double, compassAngle: Float
    ) {
        gpsAndCompassData.value?.add(GpsAndCompassInfo(timestamp, latitude, longitude, compassAngle))
        gpsAndCompassData.notifyObservers()
    }

    /**
     * Returns the list of beacons.
     * @return The list of beacons.
     */
    fun getBeacons(): ArrayList<BeaconSimplified> {
        return beaconsData.value!!
    }

    /**
     * Returns the beacon with the given identifier.
     * @param id The identifier of the beacon.
     * @return The beacon with the given identifier.
     */
    fun getBeacon(id: Identifier): BeaconSimplified? {
        return beaconsData.value?.find { it.id == id }
    }

    /**
     * Removes all the data from the session.
     */
    fun clear() {
        beaconsData.value?.clear()
        gpsAndCompassData.value?.clear()
        beaconsData.notifyObservers()
        gpsAndCompassData.notifyObservers()
        startZonedDateTime = null
        stopZonedDateTime = null
    }

    /**
     * Removes the beacon with the given identifier from the list.
     * @param id The identifier of the beacon.
     */
    fun removeBeacon(id: Identifier) {
        beaconsData.value?.removeIf { it.id == id }
        beaconsData.notifyObservers()
    }

    /**
     * Save the data to temporary files.
     * Allows updating and merging them later. This is expected to be called when too much data is
     * in memory.
     */
    fun freeDataTemporarily() {
        // create the temporary files if they do not exist
        if (gpsAndCompassDataCacheFile == null) {
            gpsAndCompassDataCacheFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_EXTENSION, cacheDir)
        }
        if (beaconsDataCacheFile == null) {
            beaconsDataCacheFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_EXTENSION, cacheDir)
        }

        // append the latest data to the temporary files
        gpsAndCompassDataCacheFile!!.outputStream().writer(Charsets.US_ASCII).use {
            SessionWriter.V2.appendCsvBodyFromTempFile(it, gpsAndCompassDataCacheFile!!)
            it.close()
        }
        beaconsDataCacheFile!!.outputStream().writer(Charsets.US_ASCII).use {
            SessionWriter.V2.appendCsvBodyFromBeacons(it, zone, beacons.value!!)
            it.close()
        }
        // clear the data from the beacons to free memory
        beacons.value?.forEach { it.sensorData.value?.clear() }
    }

    /**
     * Conclude the session and save it to a file.
     * Gets the data from temporary dumps and the current readings.
     * @return The file with the session data.
     */
    fun saveSession(): File? {
        // exit if there is no data to save
        @Suppress("UsePropertyAccessSyntax")
        if ((beacons.value == null)  // no beacons
            || (beacons.value?.isEmpty() == true)  // no beacons
            || (beacons.value?.all { beacon -> beacon.sensorData.value?.isEmpty() != false } == true)  // all beacons are empty
            || (beacons.value?.any { beacon -> beacon.statusValue.value != BeaconSimplifiedStatus.INFO_MISSING } == false)  // not a single beacon has complete info
            || (gpsAndCompassData.value == null)  // no GPS and compass data
            || (gpsAndCompassData.value?.isEmpty() == true)  // no GPS and compass data
        ) {
            return null
        }

        // deep copy all beacons and their data to a temporary variable, and empty them
        val temporaryBeacons =
            beacons.value!!.filter { it.statusValue.value != BeaconSimplifiedStatus.INFO_MISSING }
                .map { it.copy() }
        beacons.value!!.map { it.clear() }
        // deep copy the GPS and compass data to a temporary variable, and empty it
        val temporaryGpsAndCompassData = gpsAndCompassData.value!!.map { it.copy() }
        gpsAndCompassData.value!!.clear()

        val startInstant = ZonedDateTime.ofInstant(startZonedDateTime!!.toInstant(), ZoneId.of("UTC"))
        val stopInstant = ZonedDateTime.ofInstant(stopZonedDateTime!!.toInstant(), ZoneId.of("UTC"))
        var outFile = File(
            cacheDir,
            "${SESSION_FILE_PREFIX}${startInstant}-${stopInstant}${SESSION_FILE_EXTENSION}"
        )

        outFile.outputStream().writer(Charsets.UTF_8).use {
            // write the header
            SessionWriter.V2.createJSONHeader(
                it,
                TimeZone.getDefault(),
                temporaryBeacons,
                startInstant!!,
                stopInstant!!
            )
            it.write("\n\n")  // separate the header from the bodies
            // write the beacon CSV header
            SessionWriter.V2.appendCsvBeaconHeader(it)
            // write the beacon body from the cached file
            if (beaconsDataCacheFile != null) {
                SessionWriter.V2.appendCsvBodyFromTempFile(it, beaconsDataCacheFile!!)
            }
            // write the beacons body from the beacons list
            SessionWriter.V2.appendCsvBodyFromBeacons(it, zone, temporaryBeacons)
            it.write("\n\n")  // separate the bodies
            // write the GPS and compass CSV header
            SessionWriter.V2.appendCsvInternalSensorsHeader(it)
            // write the GPS and compass body from the cached file
            if (gpsAndCompassDataCacheFile != null) {
                SessionWriter.V2.appendCsvBodyFromTempFile(it, gpsAndCompassDataCacheFile!!)
            }
            // write the GPS and compass body from the GPS and compass data list
            SessionWriter.V2.appendCsvBodyFromInternalSensors(it, zone, temporaryGpsAndCompassData)

            it.close()
        }

        // clear the temporary files
        gpsAndCompassDataCacheFile?.delete()
        beaconsDataCacheFile?.delete()

        // return the file to the caller
        return outFile
    }

    /**
     * Begins a new session.
     */
    fun startSession() {
        clear()
        startZonedDateTime = ZonedDateTime.now()
    }

    /**
     * Ends the current session by saving it to the cache dir.
     * @return The file with the session data.
     */
    fun concludeSession(): File? {
        stopZonedDateTime = ZonedDateTime.now()
        return saveSession()
    }

    /**
     * Return a list of Files with the session data in the cache folder, that can be uploaded.
     * Unfinished sessions (i.e., the current one) are not included.
     */
    fun getSessionFiles(): Array<File> {
        val files = cacheDir!!.listFiles { _, name ->
            name.startsWith(SESSION_FILE_PREFIX) && name.endsWith(SESSION_FILE_EXTENSION)
        }
        // filter out the cached body file
        return files
    }
}
