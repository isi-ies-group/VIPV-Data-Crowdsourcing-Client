package es.upm.ies.surco.session_logging

import android.os.FileObserver
import java.io.File

class SessionFilesObserver(
    private val directory: File, private val onFileChanged: (List<File>) -> Unit
) {
    private val fileObserver = object :
        FileObserver(directory.absolutePath, CREATE or DELETE) {  // update when minSdk >= 29
        override fun onEvent(event: Int, path: String?) {
            if (path != null) {
                // Update the session files list when a file is created or deleted
                onFileChanged(directory.listFiles()?.toList() ?: emptyList())
            }
        }
    }

    fun start() {
        fileObserver.startWatching()
    }

    fun stop() {
        fileObserver.stopWatching()
    }
}