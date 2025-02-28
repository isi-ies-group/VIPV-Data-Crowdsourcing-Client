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
import androidx.navigation.fragment.findNavController
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.FragmentHomeBinding
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.session_logging.BeaconSimplified

class FragHome : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    lateinit var viewModel: Lazy<FragHomeViewModel>

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
        viewModel = viewModels<FragHomeViewModel>()

        // Get the application instance
        appMain = AppMain.Companion.instance

        // Create the adapter for the list view and assign it to the list view.
        adapter = ListAdapterBeacons(requireContext(), ArrayList(), viewLifecycleOwner)
        binding.beaconListView.adapter = adapter

        // Set the start stop button text and icon according to the session state
        updateStartStopButton(appMain.isSessionActive.value!!)

        // Assign observers and callbacks to the ViewModel's LiveData objects.
        viewModel.value.rangedBeacons.observe(viewLifecycleOwner) { beacons ->
            adapter.updateData(beacons)
        }

        binding.beaconListView.onItemClickListener =
            AdapterView.OnItemClickListener { parent, view, position, id ->
                val beacon = parent.getItemAtPosition(position) as BeaconSimplified
                val beaconId = beacon.id
                Log.d("FragHome", "Beacon clicked: $beaconId")
                // navigate to the details fragment, passing the beacon ID
                findNavController().navigate(
                    R.id.action_homeFragment_to_fragBeaconDetails,
                    Bundle().apply {
                        putString("beaconId", beaconId.toString())
                    })
            }

        viewModel.value.nRangedBeacons.observe(viewLifecycleOwner) { n ->
            updateBeaconCountTextView(n, appMain.isSessionActive.value!!)
            adapter.updateData(viewModel.value.rangedBeacons.value!!)
        }

        viewModel.value.isSessionActive.observe(viewLifecycleOwner) { isSessionActive ->
            updateStartStopButton(isSessionActive)
            updateBeaconCountTextView(viewModel.value.nRangedBeacons.value!!, isSessionActive)
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Set click listeners for the buttons
        binding.startStopSessionButton.setOnClickListener {
            if (viewModel.value.isSessionActive.value == false) {
                // Check if Bluetooth is enabled and prompt the user to enable it if not
                promptEnableBluetooth()
                // Check compass precision
                promptAlertOnLowCompassPrecision()
            }
            viewModel.value.toggleSession()
        }

        binding.imBtnActionUploadSession.setOnClickListener {
            // Check if there is data to upload
            if (AppMain.Companion.instance.loggingSession.getSessionFiles().isEmpty()) {
                // If there are no files, show a toast message and return
                Toast.makeText(
                    requireContext(), getString(R.string.no_data_to_upload), Toast.LENGTH_SHORT
                ).show()
            } else {
                // Toast that the data is being uploaded
                Toast.makeText(
                    requireContext(), getString(R.string.uploading_session_data), Toast.LENGTH_SHORT
                ).show()
                // Upload the session data
                viewModel.value.uploadAllSessions()
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
     * @param isSessionActive True if the session is active, false otherwise.
     */
    private fun updateStartStopButton(isSessionActive: Boolean) {
        if (isSessionActive) {
            binding.startStopSessionButton.setImageResource(R.drawable.square_stop)
            binding.startStopSessionButton.tooltipText = getString(R.string.stop_button)
            binding.startStopSessionButton.contentDescription = getString(R.string.stop_button)
        } else {
            binding.startStopSessionButton.setImageResource(R.drawable.triangle_start)
            binding.startStopSessionButton.tooltipText = getString(R.string.start_button)
            binding.startStopSessionButton.contentDescription = getString(R.string.start_button)
        }
    }

    /**
     * Updates the text view with the number of beacons detected, and whether the session is paused
     * or not.
     * @param nRangedBeacons The number of beacons detected.
     * @param isSessionActive True if the session is active, false otherwise.
     */
    private fun updateBeaconCountTextView(nRangedBeacons: Int, isSessionActive: Boolean) {
        if (isSessionActive) {
            // Update the top message textview to show the number of beacons detected
            if (nRangedBeacons == 0) {
                binding.beaconCountTextView.text = getString(R.string.beacons_detected_zero)
            } else {
                binding.beaconCountTextView.text =
                    getString(R.string.beacons_detected_nonzero, nRangedBeacons)
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
