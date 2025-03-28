package es.upm.ies.surco.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.session_logging.BeaconSimplified

@OptIn(ExperimentalStdlibApi::class)
class FragHomeViewModel() : ViewModel() {
    private val appMain = AppMain.Companion.instance

    private val _nRangedBeacons = MutableLiveData<Int>()
    val nRangedBeacons: LiveData<Int> get() = _nRangedBeacons
    val rangedBeacons: LiveData<ArrayList<BeaconSimplified>> = appMain.loggingSession.beacons
    val isSessionActive: LiveData<Boolean> = appMain.isSessionActive

    init {
        // update the number of beacons detected
        appMain.nRangedBeacons.observeForever { n ->
            _nRangedBeacons.value = n?.toInt()
        }
    }

    fun startSession() {
        appMain.startSession()
    }

    fun toggleSession() {
        appMain.toggleSession()
    }

    fun uploadAllSessions() {
        appMain.uploadAll()
    }
}
