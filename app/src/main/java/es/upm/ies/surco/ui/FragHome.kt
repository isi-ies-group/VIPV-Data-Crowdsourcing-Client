package es.upm.ies.surco.ui

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
import android.bluetooth.BluetoothManager
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.FragmentHomeBinding
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.hideKeyboard
import es.upm.ies.surco.session_logging.BeaconSimplified
import es.upm.ies.surco.session_logging.LoggingSessionStatus

class FragHome : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FragHomeViewModel by viewModels(
        factoryProducer = {
            ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        })

    // Adapter for the list view
    lateinit var adapter: ListAdapterBeacons

    // Application instance
    lateinit var appMain: AppMain

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager =
            requireActivity().getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothEnablingResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            // User dismissed or denied Bluetooth prompt
            promptEnableBluetooth()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        // Get the application instance
        appMain = requireActivity().application as AppMain

        // Create the adapter for the list view and assign it to the list view.
        adapter = ListAdapterBeacons(requireContext(), ArrayList(), viewLifecycleOwner)
        binding.beaconListView.adapter = adapter

        // Set the start stop button text and icon according to the session state
        updateStartStopButton(
            viewModel.loggingSessionStatus.value ?: LoggingSessionStatus.SESSION_STOPPING
        )

        // Assign observers and callbacks to the ViewModel's LiveData objects.
        viewModel.rangedBeacons.observe(viewLifecycleOwner) { beacons ->
            adapter.updateData(beacons)
        }

        binding.beaconListView.onItemClickListener =
            AdapterView.OnItemClickListener { parent, view, position, id ->
                val beacon = parent.getItemAtPosition(position) as BeaconSimplified
                val beaconId = beacon.id
                Log.d("FragHome", "Beacon clicked: $beaconId")
                // navigate to the details fragment, passing the beacon ID
                findNavController().navigate(
                    R.id.action_homeFragment_to_fragBeaconDetails, Bundle().apply {
                        putString("beaconId", beaconId.toString())
                    })
            }

        viewModel.nBeaconsOnline.observe(viewLifecycleOwner) { n ->
            updateBeaconCountTextView(
                n, viewModel.loggingSessionStatus.value == LoggingSessionStatus.SESSION_ONGOING
            )
            adapter.updateData(viewModel.rangedBeacons.value!!)
        }

        viewModel.loggingSessionStatus.observe(viewLifecycleOwner) { status ->
            updateStartStopButton(status)
            updateBeaconCountTextView(
                viewModel.nBeaconsOnline.value!!, status == LoggingSessionStatus.SESSION_ONGOING
            )
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideKeyboard() // Close the virtual keyboard
        // Set click listeners for the buttons
        binding.startStopSessionButton.setOnClickListener {
            if (viewModel.loggingSessionStatus.value == LoggingSessionStatus.SESSION_TRIGGERABLE) {
                // Check if Bluetooth is enabled and prompt the user to enable it if not
                promptEnableBluetooth()
                // Check compass precision
                promptAlertOnLowCompassPrecision()
            }
            viewModel.toggleSession()
        }

        binding.imBtnActionUploadSession.setOnClickListener {
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
                viewModel.uploadAllSessions()
            }
        }

        binding.imBtnActionManageSessions.setOnClickListener {
            // Navigate to the manage sessions fragment
            findNavController().navigate(R.id.action_homeFragment_to_fragManageSessions)
        }

        binding.beaconCountTextView.text = getString(R.string.beacons_detected_zero)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Updates the start/stop session button icon according to the session state, as well as the
     * content description.
     * @param status The current session status.
     */
    private fun updateStartStopButton(status: LoggingSessionStatus) {
        when (status) {
            LoggingSessionStatus.SESSION_ONGOING -> {
                binding.startStopSessionButton.setImageResource(R.drawable.square_stop)
                binding.startStopSessionButton.tooltipText = getString(R.string.stop_button)
                binding.startStopSessionButton.contentDescription = getString(R.string.stop_button)

                binding.startStopSessionButton.isEnabled = true
                binding.startStopSessionButton.isClickable = true
            }

            LoggingSessionStatus.SESSION_TRIGGERABLE -> {
                binding.startStopSessionButton.setImageResource(R.drawable.triangle_start)
                binding.startStopSessionButton.tooltipText = getString(R.string.start_button)
                binding.startStopSessionButton.contentDescription = getString(R.string.start_button)

                binding.startStopSessionButton.isEnabled = true
                binding.startStopSessionButton.isClickable = true
            }

            LoggingSessionStatus.SESSION_STOPPING -> {
                // Just disable the button
                binding.startStopSessionButton.isEnabled = false
                binding.startStopSessionButton.isClickable = false
            }
        }
    }

    /**
     * Updates the text view with the number of beacons detected, and whether the session is paused
     * or not.
     * @param nBeacons The number of beacons detected.
     * @param isSessionActive True if the session is active, false otherwise.
     */
    private fun updateBeaconCountTextView(nBeacons: Int, isSessionActive: Boolean) {
        if (isSessionActive) {
            // Update the top message textview to show the number of beacons detected
            if (nBeacons == 0) {
                binding.beaconCountTextView.text = getString(R.string.beacons_detected_zero)
            } else {
                binding.beaconCountTextView.text =
                    getString(R.string.beacons_detected_nonzero, nBeacons)
            }
        } else {
            binding.beaconCountTextView.text = getString(R.string.beacons_detected_paused)
        }
    }

    /**
     * Prompts the user to enable Bluetooth via a system dialog.
     *
     * For Android 12+, BLUETOOTH_CONNECT is required to use
     * the [BluetoothAdapter.ACTION_REQUEST_ENABLE] intent.
     */
    private fun promptEnableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ActPermissions.Companion.permissionGranted(
                requireContext(), BLUETOOTH_CONNECT
            )
        ) {
            // Insufficient permission to prompt for Bluetooth enabling
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Intent(ACTION_REQUEST_ENABLE).apply {
                bluetoothEnablingResult.launch(this)
            }
        }
    }

    /**
     * Alerts the user if the compass precision is low and asks them to recalibrate it.
     */
    private fun promptAlertOnLowCompassPrecision() {
        appMain.sensorAccuracyValue.value?.let {
            if (it <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                val builder = AlertDialog.Builder(requireContext())
                builder.setTitle(getString(R.string.low_compass_precision_title))
                builder.setMessage(getString(R.string.low_compass_precision_message))
                builder.setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                builder.create().show()
            }
        }
    }
}
