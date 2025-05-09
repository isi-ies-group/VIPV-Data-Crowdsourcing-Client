package es.upm.ies.surco.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.BuildConfig
import es.upm.ies.surco.R
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.databinding.FragmentManageSessionsBinding
import es.upm.ies.surco.databinding.RowItemSessionFileBinding
import es.upm.ies.surco.session_logging.SessionFilesObserver
import java.io.File

class FragManageSessions : Fragment() {

    private var _binding: FragmentManageSessionsBinding? = null
    private val binding get() = _binding!!
    private lateinit var sessionFilesAdapter: SessionFilesAdapter
    private lateinit var appMain: AppMain
    private lateinit var sessionFilesObserver: SessionFilesObserver

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentManageSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appMain = requireActivity().application as AppMain
        sessionFilesObserver = SessionFilesObserver(appMain.cachedSessionsDir) { files ->
            // Ensure UI updates happen on main thread
            activity?.runOnUiThread {
                if (isAdded) { // Check fragment is still attached
                    updateSessionFileList(files)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionFilesObserver.start() // Start observing here

        sessionFilesAdapter = SessionFilesAdapter(appMain.loggingSession.getSessionFiles().asList())
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sessionFilesAdapter
            addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
        }

        updateStatusText()

        binding.fabDeleteAll.setOnClickListener {
            // exit if empty list of session files
            if (appMain.loggingSession.getSessionFiles().isEmpty()) {
                return@setOnClickListener
            }
            // allow the user to confirm the deletion
            showDeleteConfirmationDialog()
        }
        binding.fabUploadAll.setOnClickListener {
            // Check if there is data to upload
            if (appMain.loggingSession.getSessionFiles().isEmpty()) {
                // If there are no files, show a toast message and return
                Toast.makeText(
                    requireContext(), getString(R.string.no_data_to_upload), Toast.LENGTH_SHORT
                ).show()
            } else if (appMain.apiUserSession.lastKnownState.value == ApiUserSessionState.NOT_LOGGED_IN || appMain.apiUserSession.lastKnownState.value == ApiUserSessionState.NEVER_LOGGED_IN) {
                // If the user is not logged in, show a toast message and return
                Toast.makeText(
                    requireContext(), getString(R.string.session_not_active), Toast.LENGTH_SHORT
                ).show()
            } else {
                // Toast that the data is being uploaded
                Toast.makeText(
                    requireContext(), getString(R.string.uploading_session_data), Toast.LENGTH_SHORT
                ).show()
                // Upload the session data
                appMain.uploadAllSessions()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        sessionFilesObserver.stop() // Stop observing when the view is destroyed
    }

    private fun updateStatusText() {
        binding.sessionStatusTextView.text = if (sessionFilesAdapter.itemCount == 0) {
            getString(R.string.manage_sessions_no_sessions_available)
        } else {
            getString(R.string.manage_sessions_sessions_stored, sessionFilesAdapter.itemCount)
        }
    }

    private fun updateSessionFileList(files: List<File>) {
        sessionFilesAdapter.updateFiles(files)
        updateStatusText()
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(requireContext()).setTitle(getString(R.string.delete_all_sessions))
            .setMessage(getString(R.string.confirm_undonable_action))
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                appMain.deleteAllSessions { success ->
                    if (isAdded) { // Check fragment is still attached
                        activity?.runOnUiThread {
                            if (success) {
                                updateSessionFileList(emptyList())
                                Toast.makeText(
                                    context, R.string.all_sessions_deleted, Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    context, R.string.deletion_failed, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                dialog.dismiss()
            }.setNegativeButton(getString(R.string.no)) { dialog, _ ->
                dialog.dismiss()
            }.create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.warning_red)
                    )
                    getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.grey)
                    )
                }
            }.show()
    }

    class SessionFilesAdapter(private var files: List<File>) :
        RecyclerView.Adapter<SessionFilesAdapter.ViewHolder>() {

        private val selectedFiles = mutableSetOf<File>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = RowItemSessionFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.bind(file)
        }

        override fun getItemCount(): Int = files.size


        fun updateFiles(newFiles: List<File>) {
            files = newFiles
            selectedFiles.clear()
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        inner class ViewHolder(private val binding: RowItemSessionFileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(file: File) {
                binding.fileName.text = file.name

                binding.btnShare.setOnClickListener {
                    val uri = FileProvider.getUriForFile(
                        binding.root.context, BuildConfig.APPLICATION_ID + ".fileProvider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    binding.root.context.startActivity(
                        Intent.createChooser(
                            intent, "Share session file"
                        )
                    )
                }
                binding.btnDelete.setOnClickListener {
                    file.delete()
                    updateFiles(files - file)
                    Toast.makeText(binding.root.context, R.string.was_deleted, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }
}
