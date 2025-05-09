package es.upm.ies.surco.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.session_logging.BeaconSimplified
import es.upm.ies.surco.session_logging.BeaconSimplifiedStatus
import es.upm.ies.surco.session_logging.SensorEntry
import org.altbeacon.beacon.Identifier

class FragBeaconDetailsViewModel(application: Application) : AndroidViewModel(application) {
    private val appMain by lazy { getApplication<AppMain>() }
    private var beaconId: Identifier? = null

    private var _beacon = MutableLiveData<BeaconSimplified?>()
    val beacon: LiveData<BeaconSimplified?> get() = _beacon
    val status: LiveData<BeaconSimplifiedStatus>? get() = _beacon.value?.statusValue

    var sensorEntries: MutableLiveData<ArrayList<SensorEntry>> =
        MutableLiveData<ArrayList<SensorEntry>>()

    /**
     * Sets the beacon with the given identifier.
     * @param id The identifier of the beacon.
     */
    fun setBeaconId(id: Identifier) {
        if (id != beaconId) {
            beaconId = id
            _beacon.value = appMain.loggingSession.getBeacon(id)
            sensorEntries = _beacon.value?.sensorData ?: MutableLiveData<ArrayList<SensorEntry>>()
        }
    }

    /**
     * Loads the beacon with the given identifier.
     */
    fun loadBeacon(id: String?) {
        if (id != null) {
            setBeaconId(Identifier.parse(id))
        }
    }

    /**
     * Deletes the current shown beacon of this ViewModel.
     */
    fun deleteBeacon() {
        if (_beacon.value != null) {
            appMain.loggingSession.removeBeacon(_beacon.value!!.id)
            _beacon.value = null
        }
    }
}
