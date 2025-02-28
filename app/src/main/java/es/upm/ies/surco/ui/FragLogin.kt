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
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.FragmentLoginBinding
import es.upm.ies.surco.api.ApiUserSessionState

class FragLogin : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FragLoginViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment using view binding
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // observe the login status to show the user any errors or return to the main activity
        viewModel.loginStatus.observe(viewLifecycleOwner) { status ->
            if (status == ApiUserSessionState.LOGGED_IN) {
                // navigate to the main activity
                findNavController().navigate(R.id.action_fragLogin_to_homeFragment)
            } else {
                // show the user the error message
                if (status == ApiUserSessionState.ERROR_BAD_IDENTITY) {
                    binding.etEmail.error = getString(R.string.bad_email)
                } else if (status == ApiUserSessionState.ERROR_BAD_PASSWORD) {
                    binding.etPassword.error = getString(R.string.bad_password)
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
        viewModel.loginButtonEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.btnLogin.isEnabled = enabled
        }

        // observe the email and password invalid flags and set the error messages accordingly
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

        binding.etEmail.setText(viewModel.email.value, TextView.BufferType.EDITABLE)
        binding.etPassword.setText(viewModel.password.value, TextView.BufferType.EDITABLE)

        binding.etEmail.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.email.value = s.toString()
            }

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
        }
        )
        binding.etPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.password.value = s.toString()
            }

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }
        }
        )

        binding.btnLogin.setOnClickListener {
            // close the keyboard
            // Only runs if there is a view that is currently focused
            activity?.currentFocus?.let { view ->
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }

            // clear errors on editTexts
            binding.etEmail.error = null
            binding.etPassword.error = null

            // show the user the login is in progress
            binding.btnLogin.isEnabled = false
            binding.pbLogin.visibility = View.VISIBLE

            viewModel.email.value = binding.etEmail.text.toString()
            viewModel.password.value = binding.etPassword.text.toString()
            viewModel.doLogin()
        }

        binding.btnGoToRegister.setOnClickListener {
            // navigate to the register fragment
            findNavController().navigate(R.id.action_fragLogin_to_fragRegister)
        }

        binding.btnUseOffline.setOnClickListener {
            // set the app to offline mode
            viewModel.setOffLineMode()
            // navigate back-pressing to the main activity
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        // Callback to use the app in offline mode if exited without never logging in
        if (viewModel.loginStatus.value == ApiUserSessionState.NEVER_LOGGED_IN) {
            // set the app to offline mode
            viewModel.setOffLineMode()
        }
    }
}
