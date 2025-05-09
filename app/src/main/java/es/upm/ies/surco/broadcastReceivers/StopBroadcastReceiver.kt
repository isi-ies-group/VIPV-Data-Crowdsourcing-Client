package es.upm.ies.surco.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.AppMain.Companion.TAG


/**
 * Class receiver to stop the beacon scanning session
 */
class StopBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i(TAG, "Received stop signal on broadcast receiver")

        AppMain.getInstance().concludeSession()
    }
}
