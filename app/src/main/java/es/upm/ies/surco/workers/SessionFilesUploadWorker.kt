package es.upm.ies.surco.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.session_logging.LoggingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SessionFilesUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Uploading session files")
        return try {
            val files = LoggingSession.getSessionFiles()

            // Upload files using coroutines
            val allSuccessful = withContext(Dispatchers.IO) {
                files.all { file -> uploadFile(file) }
            }

            AppMain.Companion.instance.wasUploadedSuccessfully.postValue(allSuccessful)
            if (allSuccessful) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry() // Retry on failure
        }
    }

    suspend fun uploadFile(file: File): Boolean {
        val sessionState = AppMain.Companion.instance.apiUserSession.upload(file)
        // delete file if upload was successful
        val success = sessionState == ApiUserSessionState.LOGGED_IN
        if (success) {
            file.delete()
        }
        return success
    }

    companion object {
        const val TAG = "SessionFilesUploadWorker"
    }
}
