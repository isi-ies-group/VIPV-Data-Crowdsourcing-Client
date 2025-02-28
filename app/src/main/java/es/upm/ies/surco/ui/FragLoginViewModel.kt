package es.upm.ies.surco.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.upm.ies.surco.api.ApiUserSession
import es.upm.ies.surco.api.ApiUserSessionState
import es.upm.ies.surco.AppMain
import kotlinx.coroutines.launch

class FragLoginViewModel : ViewModel() {
    private val appMain = AppMain.Companion.instance

    // variables for the login form persistence between destroy and create
    var email: MutableLiveData<String> = MutableLiveData("")
    var password: MutableLiveData<String> = MutableLiveData("")

    // mutable flags for the error messages
    val emailInvalid = MutableLiveData<Boolean>()
    val passwordInvalid = MutableLiveData<Boolean>()

    // mutable status for the login process, to report errors to the user
    val loginStatus = MutableLiveData<ApiUserSessionState>()

    // mutable status for whether login button should be enabled
    val loginButtonEnabled = MutableLiveData<Boolean>()

    init {
        // observe the email and password fields for changes
        email.observeForever { onCredentialsChanged() }
        password.observeForever { onCredentialsChanged() }
    }

    fun onCredentialsChanged() {
        // enable the login button if both email and password are not empty
        val validEmail =
            email.value!!.isNotEmpty() and ApiUserSession.CredentialsValidator.isEmailValid(email.value!!)
        val validPassword =
            password.value!!.isNotEmpty() and ApiUserSession.CredentialsValidator.isPasswordValid(
                password.value!!
            )
        loginButtonEnabled.postValue(validEmail && validPassword)
        emailInvalid.postValue(!validEmail)
        passwordInvalid.postValue(!validPassword)
    }

    fun doLogin() = viewModelScope.launch {
        // call login method from the application and return the result
        // if successful, the user will be redirected to the main activity
        // else, update the loginStatus variable with the error message
        val result = appMain.apiUserSession.login(email.value!!, password.value!!)
        loginStatus.postValue(result)
    }

    fun setOffLineMode() {
        // set the application to offline mode
        appMain.apiUserSession.setOfflineMode()
    }
}