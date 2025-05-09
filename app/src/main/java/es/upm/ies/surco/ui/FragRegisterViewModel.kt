package es.upm.ies.surco.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import es.upm.ies.surco.AppMain
import es.upm.ies.surco.api.ApiUserSession
import es.upm.ies.surco.api.ApiUserSessionState
import kotlinx.coroutines.launch

class FragRegisterViewModel(application: Application) : AndroidViewModel(application) {
    private val appMain by lazy { getApplication<AppMain>() }

    // variables for the login form persistence between destroy and create
    var username: MutableLiveData<String> = MutableLiveData("")
    var email: MutableLiveData<String> = MutableLiveData("")
    var password: MutableLiveData<String> = MutableLiveData("")
    var password2: MutableLiveData<String> = MutableLiveData("")

    // mutable flags for the error messages
    val usernameInvalid = MutableLiveData<Boolean>()
    val emailInvalid = MutableLiveData<Boolean>()
    val passwordInvalid = MutableLiveData<Boolean>()
    val password2Invalid = MutableLiveData<Boolean>()

    // mutable status for the login process, to report errors to the user
    val registerStatus = MutableLiveData<ApiUserSessionState>()

    // mutable status for whether login button should be enabled
    val registerButtonEnabled = MutableLiveData<Boolean>()

    init {
        // observe the email and password fields for changes
        username.observeForever { onCredentialsChanged() }
        email.observeForever { onCredentialsChanged() }
        password.observeForever { onCredentialsChanged() }
        password2.observeForever { onCredentialsChanged() }
    }

    override fun onCleared() {
        super.onCleared()
        username.removeObserver { onCredentialsChanged() }
        email.removeObserver { onCredentialsChanged() }
        password.removeObserver { onCredentialsChanged() }
        password2.removeObserver { onCredentialsChanged() }
    }

    fun onCredentialsChanged() {
        // enable the login button if both email and password are not empty
        Log.i(
            "FragRegisterViewModel",
            "onCredentialsChanged: ${username.value} ${email.value} ${password.value} ${password2.value}"
        )
        val validUsername =
            username.value!!.isNotEmpty() and ApiUserSession.CredentialsValidator.isUsernameValid(
                username.value!!
            )
        val validEmail = email.value!!.isNotEmpty()
        val validPassword = password.value!!.isNotEmpty()
        val samePasswords = password.value == password2.value
        registerButtonEnabled.postValue(validUsername && validEmail && validPassword && samePasswords)
        usernameInvalid.postValue(!validUsername)
        emailInvalid.postValue(!validEmail)
        passwordInvalid.postValue(!validPassword)
        password2Invalid.postValue(!samePasswords)
    }

    fun doRegister() = viewModelScope.launch {
        // call login method from the application and return the result
        // if successful, the user will be redirected to the main activity
        // else, update the loginStatus variable with the error message
        val result = appMain.apiUserSession.register(
            username.value!!, email.value!!, password.value!!
        )
        registerStatus.postValue(result)
    }
}