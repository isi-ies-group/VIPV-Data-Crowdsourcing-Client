package es.upm.ies.surco.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import es.upm.ies.surco.BuildConfig
import es.upm.ies.surco.R
import es.upm.ies.surco.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.AppMain

class ActMain : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var actionBarDrawerToggle: ActionBarDrawerToggle
    private lateinit var navController: NavController

    private val app = AppMain.Companion.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureToolbar()
        configureNavigationDrawer()

        // Or if all permissions were already granted, start the session
        val arePermissionsOk = ActPermissions.Companion.allPermissionsGranted(this)
        if (!arePermissionsOk) {  // If any permission is not granted, go to permissions activity and wait for user to grant permissions
            val getAllPermissionsGranted =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                    if (result.resultCode != RESULT_OK) {
                        // If user did not grant permissions, close the app (this should not happen)
                        Toast.makeText(
                            this, "Permissions are required to continue", Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            getAllPermissionsGranted.launch(Intent(this, ActPermissions::class.java))
        }

        checkNeedFirstLogin()

        app.apiUserSession.lastKnownState.observeForever { state ->
            updateDrawerOptionsMenu()
        }

        app.wasUploadedSuccessfully.observeForever { wasUploadedSuccessfully ->
            if (wasUploadedSuccessfully) {  // If the data was uploaded successfully, show a message
                Toast.makeText(this, getString(R.string.upload_successful), Toast.LENGTH_SHORT)
                    .show()
            } // do nothing on else
        }

        // Observe the session state to keep the screen on during a session on Android 14 and higher
        // until we fix this issue in the future
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.isSessionActive.observeForever { isActive ->
                if (isActive) {
                    // Set screen to never turn off
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    // Unset screen to never turn off
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    /**
     * Check if this is the first time the user opens the app and transfer to login in that case
     */
    private fun checkNeedFirstLogin() {
        // loading of the user session from shared preferences may change the state of the user session
        // NEVER_LOGGED_IN is the default state
        val userMayWantToLogin =
            app.apiUserSession.lastKnownState.value == ApiUserSessionState.NEVER_LOGGED_IN
        if (userMayWantToLogin) {
            // Navigate to login fragment
            supportFragmentManager.findFragmentById(R.id.fragment_main)?.findNavController()
                ?.navigate(R.id.fragLogin)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.fragment_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        /** Handle navigation view item clicks here. */
        return when (item.itemId) {
            R.id.homeFragment -> {
                // Close the drawer
                binding.mainDrawerLayout.closeDrawers()
                findNavController(R.id.fragment_main).navigate(R.id.homeFragment)
                true
            }

            R.id.nav_settings -> {
                binding.mainDrawerLayout.closeDrawers()
                findNavController(R.id.fragment_main).navigate(R.id.fragSettings)
                true
            }

            R.id.nav_logout -> {
                binding.mainDrawerLayout.closeDrawers()
                app.apiUserSession.logout()
                Toast.makeText(this, getString(R.string.logged_out), Toast.LENGTH_SHORT).show()
                true
            }

            R.id.nav_login -> {
                binding.mainDrawerLayout.closeDrawers()
                findNavController(R.id.fragment_main).navigate(R.id.fragLogin)
                true
            }

            R.id.nav_about -> {
                binding.mainDrawerLayout.closeDrawers()
                findNavController(R.id.fragment_main).navigate(R.id.fragAbout)
                true
            }

            R.id.nav_help -> {
                openURL("${BuildConfig.SERVER_URL}/contact")
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun configureToolbar() {
        setSupportActionBar(binding.toolbar)
        actionBarDrawerToggle = ActionBarDrawerToggle(
            this,
            binding.mainDrawerLayout,
            binding.toolbar,
            R.string.toolbar_open,
            R.string.toolbar_close
        )
        binding.mainDrawerLayout.addDrawerListener(actionBarDrawerToggle)
        actionBarDrawerToggle.isDrawerIndicatorEnabled = true
        actionBarDrawerToggle.syncState()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_main) as NavHostFragment
        navController = navHostFragment.navController

        val appBarConfiguration = AppBarConfiguration(navController.graph, binding.mainDrawerLayout)
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
    }

    private fun configureNavigationDrawer() {
        binding.navViewHost.setNavigationItemSelectedListener(this)
        updateDrawerOptionsMenu()
    }

    private fun updateDrawerOptionsMenu() {
        binding.navViewHost.post {
            // Login and logout buttons in the drawer
            val menuBtnLogin = binding.navViewHost.menu.findItem(R.id.nav_login)
            val menuBtnLogout = binding.navViewHost.menu.findItem(R.id.nav_logout)
            val isUserLoggedIn =
                app.apiUserSession.lastKnownState.value == ApiUserSessionState.LOGGED_IN
            menuBtnLogin.isVisible = isUserLoggedIn != true
            menuBtnLogout.isVisible = !menuBtnLogin.isVisible
            binding.navViewHost.invalidate()
        }
    }

    fun openURL(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening link: $url; ${e.message}")
            Toast.makeText(this, "Could not open: $url", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "ActMain"
    }
}
