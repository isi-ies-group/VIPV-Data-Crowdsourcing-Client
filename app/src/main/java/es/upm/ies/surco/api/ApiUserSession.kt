package es.upm.ies.surco.api

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2KtResult
import com.lambdapioneer.argon2kt.Argon2Mode
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.time.Instant
import kotlin.random.Random
import androidx.core.content.edit
import es.upm.ies.surco.api.ApiModels.LoginRequest
import es.upm.ies.surco.api.ApiModels.RegisterRequest

enum class ApiUserSessionState {
    LOGGED_IN,  // user has logged in successfully
    NOT_LOGGED_IN,  // user logged out (may not want to be prompted to log in again)
    NEVER_LOGGED_IN,  // user has never logged in

    // errors
    CLIENT_DEPRECATED_WARNING, CLIENT_TOO_OLD_ERROR, ERROR_BAD_IDENTITY, ERROR_BAD_PASSWORD, CONNECTION_ERROR,
}

class ApiUserSession {
    var apiService: APIService
    var sharedPrefs: SharedPreferences

    // members
    var username: String? = null
    var email: String? = null
    var passHash: String? = null
    var passSalt: String? = null
    private val _state = MutableLiveData<ApiUserSessionState>(ApiUserSessionState.NEVER_LOGGED_IN)
    val state: LiveData<ApiUserSessionState> get() = _state

    var accessToken: String? = null
    var accessTokenRx: Instant? = null
    var accessTokenValidity: Int? = null

    constructor(sharedPrefs: SharedPreferences, apiService: APIService) {
        this.sharedPrefs = sharedPrefs
        this.apiService = apiService

        this.username = sharedPrefs.getString("username", null)
        this.email = sharedPrefs.getString("email", null)
        this.passHash = sharedPrefs.getString("passHash", null)
        this._state.value = sharedPrefs.getString("state", "NEVER_LOGGED_IN")
            ?.let { ApiUserSessionState.valueOf(it) } ?: ApiUserSessionState.NEVER_LOGGED_IN
    }

    // methods
    fun saveToSharedPreferences(state: ApiUserSessionState) {
        Log.i("ApiUserSession", "Saving state: $state")
        this.sharedPrefs.edit {
            putString("username", username)
            putString("email", email)
            putString("passHash", passHash)
            putString("state", state.toString())
            apply()
        }
    }

    fun logout() {
        this.sharedPrefs.edit {
            remove("username")
            remove("email")
            remove("passHash")
            remove("state")
            apply()
        }
        this.username = null
        this.email = null
        this.passHash = null
        this.passSalt = null
        this.accessToken = null
        this.accessTokenRx = null
        this.accessTokenValidity = null
        _state.value = ApiUserSessionState.NOT_LOGGED_IN
        saveToSharedPreferences(ApiUserSessionState.NOT_LOGGED_IN)
    }

    /**
     * Log in a user with the server
     * @param username the username of the user
     * @param passWord the password of the user
     * @return the state of the user session after the login
     *
     * This function will set the username, passHash, and passSalt fields of the user session object,
     * then request the salt for the user from the server, then hashes the password with the salt
     * using Argon2, and sends the login request to the server.
     *
     * If the server responds with a successful login, the function will return LOGGED_IN.
     * If the server responds with an error, the function will return ERROR_BAD_PASSWORD or CONNECTION_ERROR.
     */
    suspend fun login(email: String, passWord: String): ApiUserSessionState {
        this.email = email
        this.passSalt = null

        var knownState = ApiUserSessionState.CONNECTION_ERROR

        // get salt from server
        try {
            val saltResponse = apiService.getUserSalt(email)
            this.passSalt = saltResponse.passSalt
        } catch (e: HttpException) {
            Log.e("ApiUserSession", "Error getting salt: ${e.message}")
            knownState = ApiUserSessionState.ERROR_BAD_IDENTITY
        } catch (e: Exception) {
            Log.e("ApiUserSession", "Error getting salt: ${e.message}")
            knownState = ApiUserSessionState.CONNECTION_ERROR
        }

        if (this.passSalt == null) {
            this._state.value = knownState
            return knownState
        }

        val passWordByteArray = passWord.toByteArray()
        // passSalt is encoded in base64, decode it
        val saltByteArray = Base64.decode(this.passSalt, Base64.DEFAULT)

        // hash password with salt, store in .passHash as plaintext
        val argon2Kt = Argon2Kt()
        val hashResult: Argon2KtResult = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passWordByteArray,
            salt = saltByteArray,
            tCostInIterations = 6,
            mCostInKibibyte = 65536,
        )
        this.passHash = hashResult.encodedOutputAsString()

        // send login request to server
        val loginRequest = LoginRequest(this.email!!, this.passHash!!)
        try {
            val loginResponse = apiService.loginUser(loginRequest)
            this.username = loginResponse.username
            this.accessToken = "Bearer ${loginResponse.access_token}"
            this.accessTokenRx = Instant.now()
            this.accessTokenValidity = loginResponse.validity
            knownState = ApiUserSessionState.LOGGED_IN
        } catch (e: HttpException) {
            Log.e("ApiUserSession", "HttpException logging in user: ${e.message}")
            knownState = ApiUserSessionState.ERROR_BAD_PASSWORD
        } catch (e: Exception) {
            Log.e("ApiUserSession", "Exception logging in user: ${e.message}")
            knownState = ApiUserSessionState.CONNECTION_ERROR
        }
        this._state.value = knownState
        // persist state between runs
        saveToSharedPreferences(knownState)
        return knownState
    }

    /**
     * Register a new user with the server
     * @param username the username of the new user
     * @param email the email of the new user
     * @param passWord the password of the new user
     * @return the state of the user session after the registration
     *
     * This function will set the username, email, passHash, and passSalt fields of the user session
     * object, then send a register request to the server.
     * If the server responds with a successful registration, the function will return REGISTERED.
     * If the server responds with an error, the function will return ERROR_BAD_PASSWORD or CONNECTION_ERROR.
     */
    suspend fun register(username: String, email: String, passWord: String): ApiUserSessionState {
        this.username = username
        this.email = email

        var knownState = ApiUserSessionState.CONNECTION_ERROR

        // create a random salt for the user
        var saltByteArray = ByteArray(16) { Random.nextInt().toByte() }

        val passWordByteArray = passWord.toByteArray()

        // hash password with salt, store in passHash
        val argon2Kt = Argon2Kt()
        val hashResult: Argon2KtResult = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = passWordByteArray,
            salt = saltByteArray,
            tCostInIterations = 6,
            mCostInKibibyte = 65536,
            parallelism = 2,
        )
        this.passHash = hashResult.encodedOutputAsString()

        // send register request to server
        val registerRequest = RegisterRequest(
            this.username!!,
            this.email!!,
            this.passHash!!,
            Base64.encodeToString(saltByteArray, Base64.DEFAULT)
        )
        try {
            apiService.registerUser(registerRequest)
            knownState = ApiUserSessionState.LOGGED_IN
        } catch (e: HttpException) {
            Log.e("ApiUserSession", "HttpException registering user: ${e.message}")
            knownState = ApiUserSessionState.ERROR_BAD_IDENTITY
        } catch (e: Exception) {
            Log.e("ApiUserSession", "Exception registering user: ${e.message}")
            knownState = ApiUserSessionState.CONNECTION_ERROR
        }
        this._state.value = knownState
        // persist state between runs
        saveToSharedPreferences(knownState)
        return knownState
    }

    /**
     * Upload the session file to the server.
     * Checks the access token and if it is expired, logs in again.
     * The uploaded file is an stream of bytes, so it is not necessary to store it in the device.
     * @param file The file to upload.
     * @return The state of the user session after the upload.
     * If the server responds with a successful upload, the function will return LOGGED_IN.
     * If the server responds with an error, the function will return CONNECTION_ERROR.
     * If the access token is expired, the function will log in again.
     */
    suspend fun upload(file: File): ApiUserSessionState {
        if (accessToken == null || accessTokenRx == null || accessTokenValidity == null || accessTokenRx!!.plusSeconds(
                accessTokenValidity!!.toLong()
            ) < Instant.now()
        ) {
            // access token is expired, log in again
            // send login request to server
            val loginRequest = LoginRequest(this.email!!, this.passHash!!)
            try {
                val loginResponse = apiService.loginUser(loginRequest)
                this.username = loginResponse.username
                this.accessToken = "Bearer ${loginResponse.access_token}"
                this.accessTokenRx = Instant.now()
                this.accessTokenValidity = loginResponse.validity
                this._state.value = (ApiUserSessionState.LOGGED_IN)
            } catch (e: HttpException) {
                Log.e("ApiUserSession", "HttpException logging in user: ${e.message}")
                this._state.value = (ApiUserSessionState.ERROR_BAD_PASSWORD)
            } catch (e: Exception) {
                Log.e("ApiUserSession", "Exception logging in user: ${e.message}")
                this._state.value = (ApiUserSessionState.CONNECTION_ERROR)
            }
        }

        val filePart = MultipartBody.Part.createFormData(
            name = "file",
            filename = file.name,
            body = file.asRequestBody("plain/text".toMediaTypeOrNull())
        )
        try {
            apiService.uploadBeacons(this.accessToken!!, filePart)
        } catch (e: HttpException) {
            Log.e("ApiUserSession", "HttpException uploading file: ${e.message}")
            Log.e("ApiUserSession", "Response content: ${e.response()?.errorBody()?.string()}")
            return ApiUserSessionState.CONNECTION_ERROR
        } catch (e: Exception) {
            Log.e("ApiUserSession", "Exception uploading file: ${e.message}")
            return ApiUserSessionState.CONNECTION_ERROR
        }
        return ApiUserSessionState.LOGGED_IN
    }

    fun setOfflineMode() {
        this._state.value = ApiUserSessionState.NOT_LOGGED_IN
        saveToSharedPreferences(ApiUserSessionState.NOT_LOGGED_IN)
    }

    // sub classes and factories from root class

    fun loginRequest() = LoginRequest(this.email!!, this.passHash!!)


    fun registerRequest() =
        RegisterRequest(this.username!!, this.email!!, this.passHash!!, this.passSalt!!)


    // helper object for credentials validation
    object CredentialsValidator {
        // regex validators
        val emailValidator =
            Regex("(?:[a-zA-Z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[a-zA-Z0-9!#\$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?|\\[(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?|[a-zA-Z0-9-]*[a-zA-Z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)])")
        val usernameValidator = Regex("^[a-zA-Z0-9_]{5,20}$")

        fun isEmailValid(email: String): Boolean {
            return emailValidator.matches(email)
        }

        fun isUsernameValid(username: String): Boolean {
            return usernameValidator.matches(username)
        }

        fun isPasswordValid(password: String): Boolean {
            return password.length >= 8
        }
    }
}
