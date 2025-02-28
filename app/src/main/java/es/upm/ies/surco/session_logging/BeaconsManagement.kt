package es.upm.ies.surco.session_logging

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.altbeacon.beacon.Identifier
import java.time.Instant

/**
 * In-beacon data entry with the data that changes through a measurement session.
 * Holds the read data (16-bit 2's complement) from the analog channel of the
 * beacon, location, compass azimuth and timestamp.
 */
data class SensorEntry(
    val timestamp: Instant,
    val data: Short,
    val latitude: Float,
    val longitude: Float,
    val azimuth: Float,
)

/**
 * Status of the beacon simplified.
 */
enum class BeaconSimplifiedStatus {
    OK, OFFLINE, INFO_MISSING,
}

/**
 * Represents a beacon with its identifier and its data. This one is different from the one in the
 * AltBeacon library: this one holds exclusively the data expected to be used in conjunction with
 * the configured beacons and the API service.
 * @param id The identifier of the beacon.
 * @property sensorData The data received from the beacon.
 */
class BeaconSimplified(val id: Identifier) {
    /**
     * The data received from the analog channel of the beacon. From the NanoBeacon Config Tool User Guide EN.pdf:
     * "The ADC data is of 16-bit in 2’s complement format."
     * Set initial capacity of 360.
     */
    var sensorData: MutableLiveData<ArrayList<SensorEntry>> =
        MutableLiveData<ArrayList<SensorEntry>>(ArrayList(360))
    private var description: String = ""
    val descriptionValue get() = description
    private var position: String = ""
    val positionValue get() = position
    private var direction: Float? = null
    val directionValue get() = direction
    private var tilt: Float? = null
    val tiltValue get() = tilt
    private val status: MutableLiveData<BeaconSimplifiedStatus> =
        MutableLiveData(BeaconSimplifiedStatus.INFO_MISSING)
    val statusValue: LiveData<BeaconSimplifiedStatus> get() = status

    /**
     * Set the description, tilt, direction and position of the beacon, and refresh the status.
     */
    fun setDescription(description: String) {
        this.description = description
        refreshStatus()
    }

    fun setTilt(tilt: Float?) {
        this.tilt = tilt
        refreshStatus()
    }

    fun setDirection(direction: Float?) {
        this.direction = direction
        refreshStatus()
    }

    fun setPosition(position: String) {
        this.position = position
        if (this.position == "select_position_default") {
            this.position = ""
        }
        refreshStatus()
    }

    /**
     * Refreshes the status of the beacon.
     *
     * If the last data entry is older than the maximum offset, the status is set to NOT_IN_RANGE.
     * If the info fields are empty, the status is set to INVALID_INFO.
     * Else, the status is set to OK.
     */
    fun refreshStatus() {
        val lastEntry = sensorData.value?.lastOrNull()
        val now = Instant.now()
        if (lastEntry != null && now.epochSecond - lastEntry.timestamp.epochSecond > MAX_SECONDS_OFFSET_UNTIL_OUT_OF_RANGE) {
            status.postValue(BeaconSimplifiedStatus.OFFLINE)
            return
        } else if (position.isEmpty() || direction == null || tilt == null) {
            status.postValue(BeaconSimplifiedStatus.INFO_MISSING)
            return
        } else {
            status.postValue(BeaconSimplifiedStatus.OK)
        }
    }

    /**
     * Deep copy of the beacon data.
     */
    fun copy(): BeaconSimplified {
        val copy = BeaconSimplified(id)
        val originalSensorData = sensorData.value ?: emptyList()
        val copiedSensorData = ArrayList<SensorEntry>(originalSensorData.size)
        copiedSensorData.addAll(originalSensorData)
        copy.sensorData = MutableLiveData(copiedSensorData)
        copy.description = description
        copy.direction = direction
        copy.tilt = tilt
        return copy
    }

    /**
     * Clears the data of the beacon.
     */
    fun clear() {
        sensorData.value?.clear()
        description = ""
        direction = null
        tilt = null
    }

    /**
     * toString representation of the beacon.
     */
    override fun toString(): String {
        return "BeaconSimplified(id=$id, position='$position', direction=$direction, tilt=$tilt, status=${status.value}, description='$description')"
    }

    companion object {
        const val MAX_SECONDS_OFFSET_UNTIL_OUT_OF_RANGE = 5
    }
}
