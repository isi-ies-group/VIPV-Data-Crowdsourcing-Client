package es.upm.ies.surco.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.FragmentRegisterBinding

class FragRegister : Fragment() {
    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FragRegisterViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // observe the register status to show the user any errors or return to the main activity
        viewModel.registerStatus.observe(viewLifecycleOwner) { status ->
            if (status == ApiUserSessionState.LOGGED_IN) {
                // navigate to the main activity
                findNavController().navigate(R.id.action_fragRegister_to_homeFragment)
            } else {
                // show the user the error message
                if (status == ApiUserSessionState.ERROR_BAD_IDENTITY) {
                    binding.etEmail.error = getString(R.string.bad_email_already_registered)
                } else if (status == ApiUserSessionState.CONNECTION_ERROR) {
                    // Create an informative alert dialog
                    val builder = AlertDialog.Builder(requireContext())
                    builder.setTitle(R.string.connection_error)
                    builder.setMessage(R.string.connection_error_message)
                    builder.setPositiveButton(R.string.ok) { dialog, which ->
                        // do nothing
                    }
                    builder.show()
                }
            }
            binding.pbLogin.visibility = View.INVISIBLE
        }

        // observe the login button enabled status
        viewModel.registerButtonEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.btnRegister.isEnabled = enabled
        }

        // observe the username, email, password, and password2 invalid flags
        // and set the error messages accordingly
        viewModel.usernameInvalid.observe(viewLifecycleOwner) { invalid ->
            if (invalid) {
                binding.etUsername.error = getString(R.string.invalid_username)
            } else {
                binding.etUsername.error = null
            }
        }

        viewModel.emailInvalid.observe(viewLifecycleOwner) { invalid ->
            if (invalid) {
                binding.etEmail.error = getString(R.string.invalid_email)
            } else {
                binding.etEmail.error = null
            }
        }

        viewModel.passwordInvalid.observe(viewLifecycleOwner) { invalid ->
            if (invalid) {
                binding.etPassword.error = getString(R.string.invalid_password)
            } else {
                binding.etPassword.error = null
            }
        }

        viewModel.password2Invalid.observe(viewLifecycleOwner) { invalid ->
            if (invalid) {
                binding.etPassword2.error = getString(R.string.invalid_password_equals)
            } else {
                binding.etPassword2.error = null
            }
        }

        // set the text fields to the values in the view model
        binding.etUsername.setText(viewModel.username.value, TextView.BufferType.EDITABLE)
        binding.etEmail.setText(viewModel.email.value, TextView.BufferType.EDITABLE)
        binding.etPassword.setText(viewModel.password.value, TextView.BufferType.EDITABLE)
        binding.etPassword2.setText(viewModel.password2.value, TextView.BufferType.EDITABLE)

        // assign viewmodel text fields to the actual text fields on text changes
        binding.etUsername.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.username.value = s.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etEmail.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.email.value = s.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.password.value = s.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etPassword2.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.password2.value = s.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnRegister.setOnClickListener {
            // close the keyboard
            // Only runs if there is a view that is currently focused
            activity?.currentFocus?.let { view ->
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }

            // show the user the login is in progress
            binding.btnRegister.isEnabled = false
            binding.pbLogin.visibility = View.VISIBLE

            viewModel.email.value = binding.etEmail.text.toString()
            viewModel.password.value = binding.etPassword.text.toString()
            viewModel.doRegister()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.username.value = binding.etUsername.text.toString()
        viewModel.email.value = binding.etEmail.text.toString()
        viewModel.password.value = binding.etPassword.text.toString()
        _binding = null
    }
}
