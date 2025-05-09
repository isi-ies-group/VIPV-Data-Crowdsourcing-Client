package es.upm.ies.surco.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.session_logging.BeaconSimplified
import es.upm.ies.surco.session_logging.LoggingSessionStatus

@OptIn(ExperimentalStdlibApi::class)
class FragHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val appMain by lazy { getApplication<AppMain>() }
    val nBeaconsOnline: LiveData<Int> = appMain.loggingSession.nBeaconsOnline
    val rangedBeacons: LiveData<ArrayList<BeaconSimplified>> = appMain.loggingSession.beacons
    val loggingSessionStatus: LiveData<LoggingSessionStatus> = appMain.loggingSession.status

    fun toggleSession() {
        appMain.toggleSession()
    }

    fun uploadAllSessions() {
        appMain.uploadAllSessions()
    }
}
