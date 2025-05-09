package es.upm.ies.surco.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.BuildConfig
import es.upm.ies.surco.R
import kotlinx.coroutines.launch

class FragSettings : PreferenceFragmentCompat() {
    // lock for the test api endpoint button, so multiple requests are not sent before the first one finishes
    private var testingApiEndpoint = false

    private val appMain by lazy { requireActivity().application as AppMain }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Get the manage sessions preference
        val manageSessionsPreference = findPreference<Preference>("manage_sessions")
        if (manageSessionsPreference == null) {
            Log.w(TAG, "Manage sessions setting not found")
        }
        // set the callback to open the manage sessions fragment
        manageSessionsPreference?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_fragManageSessions)
            true
        }

        // Get the battery optimization preference
        val batteryOptimizationPreference = findPreference<Preference>("battery_optimization")
        if (batteryOptimizationPreference == null) {
            Log.w(TAG, "Battery optimization setting not found")
        }
        // set the callback to open the manage sessions fragment
        batteryOptimizationPreference?.setOnPreferenceClickListener {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }

            true
        }

        // Get session upload setting
        val sessionUploadPreference = findPreference<ListPreference>("auto_upload_behaviour")
        if (sessionUploadPreference == null) {
            Log.w(TAG, "Session upload setting not found")
        }
        // set summary to current value
        sessionUploadPreference?.summary = sessionUploadPreference.entry
        // update hint on preference change, updated value is handled by main application
        sessionUploadPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                sessionUploadPreference.summary =
                    sessionUploadPreference.entries[sessionUploadPreference.findIndexOfValue(
                        newValue as String
                    )]
                true
            }

        // Get the color theme setting
        val colorThemePreference = findPreference<ListPreference>("color_theme")
        if (colorThemePreference == null) {
            Log.w(TAG, "Color theme setting not found")
        }
        // set summary to current value
        colorThemePreference?.summary = colorThemePreference.entry
        // set callback to update the application theme when the value changes
        colorThemePreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                appMain.setupTheme(newValue as String)
                true
            }

        val scanIntervalPreference = findPreference<EditTextPreference>("scan_interval")
        // set summary to the current value
        scanIntervalPreference?.summary = appMain.scanInterval.toString() + " ms"
        scanIntervalPreference?.setOnBindEditTextListener {
            // set hint to the current value
            it.hint = appMain.scanInterval.toString() + " ms"
            // configure to only accept numerical values
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        // set callback to change the scan_interval
        scanIntervalPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                try {
                    appMain.scanInterval = (newValue as String).toLong()
                    // update summary to the current value
                    scanIntervalPreference.summary = appMain.scanInterval.toString() + " ms"
                } catch (_: NumberFormatException) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_bluetooth_scan_interval_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@OnPreferenceChangeListener false
                }
                true
            }

        // Get the API URI setting
        val editTextPreference = findPreference<EditTextPreference>("api_uri")
        if (editTextPreference == null) {
            Log.w(TAG, "API URI setting not found")
        }
        editTextPreference?.setOnBindEditTextListener {
            // set hint to default value
            it.hint = BuildConfig.SERVER_URL
        }
        // set callback to update the application API service when the value changes
        editTextPreference?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                appMain.setupApiService(newValue as String)
                true
            }

        // Get test api endpoint button preference
        val testApiEndpoint = findPreference<Preference>("api_test")
        // set callback to update the application API service when the value changes
        testApiEndpoint?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Log.d(TAG, "Testing API endpoint")
            // if the button is already pressed, do nothing
            if (testingApiEndpoint) {
                return@OnPreferenceClickListener true
            }
            // set the flag to true
            testingApiEndpoint = true
            // show a toast that the test is running
            Toast.makeText(
                requireContext(), getString(R.string.settings_api_testing), Toast.LENGTH_SHORT
            ).show()
            lifecycleScope.launch {
                val isUp = appMain.testApiEndpoint()
                // set the flag to false
                testingApiEndpoint = false
                // show a toast with the result
                Log.d(TAG, "API endpoint is ${if (isUp) "up" else "down"}")
                Toast.makeText(
                    requireContext(),
                    if (isUp) getString(R.string.settings_api_valid) else getString(R.string.settings_api_invalid),
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        }
    }

    companion object {
        /*@SuppressLint("BatteryLife")
        fun canHandleBatteryOptimizationIntent(context: Context): Boolean {
            val packageName = context.packageName
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            return intent.resolveActivity(context.packageManager) != null
        }*/

        val TAG: String = FragSettings::class.java.simpleName
    }
}