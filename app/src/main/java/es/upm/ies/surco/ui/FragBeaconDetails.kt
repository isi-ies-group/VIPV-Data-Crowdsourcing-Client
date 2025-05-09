package es.upm.ies.surco.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.R
import es.upm.ies.surco.createPositionMap
import es.upm.ies.surco.databinding.FragmentBeaconDetailsBinding
import es.upm.ies.surco.session_logging.BeaconSimplifiedStatus

class FragBeaconDetails : Fragment() {
    private val viewModel: FragBeaconDetailsViewModel by viewModels(
        factoryProducer = {
            ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        })
    private var _binding: FragmentBeaconDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var appMain: AppMain
    private lateinit var positionMap: Map<String, String>

    private val descriptionTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun afterTextChanged(s: Editable?) {
            viewModel.beacon.value?.setDescription(s.toString())
        }
    }
    private val tiltTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun afterTextChanged(s: Editable?) {
            viewModel.beacon.value?.setTilt(s.toString().toFloatOrNull())
        }
    }
    private val directionTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun afterTextChanged(s: Editable?) {
            viewModel.beacon.value?.setDirection(s.toString().toFloatOrNull())
        }
    }
    private val positionSpinnerOnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View, position: Int, id: Long
            ) {
                val localizedItemText = parent.getItemAtPosition(position).toString()
                val unlocalizedItemValue = positionMap[localizedItemText]
                viewModel.beacon.value?.setPosition(unlocalizedItemValue ?: "")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString("beaconId")?.let { viewModel.loadBeacon(it) }
        activity?.title = getString(R.string.beacon_details)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appMain = requireActivity().application as AppMain
        positionMap = createPositionMap(appMain)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment using view binding
        _binding = FragmentBeaconDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        setupListeners()
        setupSpinner()
        setupListView()
    }

    private fun setupObservers() {
        viewModel.beacon.observe(viewLifecycleOwner) { beacon ->
            if (beacon != null) {
                updateTextFields()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }

        viewModel.sensorEntries.observe(viewLifecycleOwner) { sensorEntries ->
            (binding.listViewSensorEntries.adapter as? ListAdapterSensorEntries)?.updateData(
                sensorEntries.reversed()
            )
        }

        viewModel.status?.observe(viewLifecycleOwner) { status ->
            val (drawableResource, tintColor, contentDescription) = when (status) {
                BeaconSimplifiedStatus.OFFLINE -> Triple(
                    R.drawable.bluetooth_off,
                    R.color.warning_red,
                    getString(R.string.beacon_detail_out_of_range_error)
                )

                BeaconSimplifiedStatus.INFO_MISSING -> Triple(
                    R.drawable.warning,
                    R.color.warning_orange,
                    getString(R.string.beacon_detail_info_error)
                )

                else /* OK */ -> Triple(
                    R.drawable.check, R.color.green_ok, getString(R.string.beacon_detail_ok)
                )
            }
            binding.tvBeaconStatusMessage.setCompoundDrawablesWithIntrinsicBounds(
                drawableResource, 0, 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(
                binding.tvBeaconStatusMessage,
                ContextCompat.getColorStateList(requireContext(), tintColor)
            )
            binding.tvBeaconStatusMessage.text = contentDescription
        }
    }

    private fun setupListeners() {
        binding.imBtnDeleteBeacon.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle(getString(R.string.empty_all_data))
                .setMessage(getString(R.string.empty_all_data_confirmation))
                .setPositiveButton(getString(R.string.yes)) { _, _ -> viewModel.deleteBeacon() }
                .setNegativeButton(getString(R.string.no), null).create().apply {
                    // Optional: Customize button colors
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                            ContextCompat.getColor(
                                requireContext(), R.color.warning_red
                            )
                        )
                        getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                            ContextCompat.getColor(
                                requireContext(), R.color.grey
                            )
                        )
                    }
                }.show()
        }

        /** note listeners for editTexts and spinner are added in updateTextFields() */
    }

    private fun setupSpinner() {
        val positions = positionMap.keys.toList()
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, positions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPosition.adapter = adapter
        binding.spinnerPosition.setSelection(0) // Set default item as selected
    }

    private fun setupListView() {
        val adapter = ListAdapterSensorEntries(requireContext(), ArrayList())
        binding.listViewSensorEntries.adapter = adapter
    }

    private fun updateTextFields() {
        val beacon = viewModel.beacon.value
        if (beacon != null) {
            // Remove listeners to avoid triggering the updateBeaconInfo method
            binding.editTextDescription.removeTextChangedListener(descriptionTextWatcher)
            binding.editTextTilt.removeTextChangedListener(tiltTextWatcher)
            binding.editTextDirection.removeTextChangedListener(directionTextWatcher)
            binding.spinnerPosition.onItemSelectedListener = null

            binding.tvBeaconIdentifier.text = viewModel.beacon.value?.id.toString()
            binding.editTextDescription.setText(viewModel.beacon.value?.descriptionValue)
            val tilt = viewModel.beacon.value?.tiltValue?.toInt()?.toString()
            binding.editTextTilt.setText(tilt ?: "")
            val direction = viewModel.beacon.value?.directionValue?.toInt()?.toString()
            binding.editTextDirection.setText(direction ?: "")

            val positionKey =
                positionMap.entries.find { it.value == viewModel.beacon.value?.positionValue }?.key
            val positionIndex = positionMap.keys.indexOf(positionKey)
            if (positionIndex >= 0) {
                binding.spinnerPosition.setSelection(positionIndex)
            }

            // Add listeners back
            // Reattach TextWatchers and OnItemSelectedListener
            binding.editTextDescription.addTextChangedListener(descriptionTextWatcher)
            binding.editTextTilt.addTextChangedListener(tiltTextWatcher)
            binding.editTextDirection.addTextChangedListener(directionTextWatcher)
            binding.spinnerPosition.onItemSelectedListener = positionSpinnerOnItemSelectedListener
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
