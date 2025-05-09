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

class FragLoginViewModel(application: Application) : AndroidViewModel(application) {
    private val appMain by lazy { getApplication<AppMain>() }

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

    override fun onCleared() {
        super.onCleared()
        email.removeObserver { onCredentialsChanged() }
        password.removeObserver { onCredentialsChanged() }
    }

    fun onCredentialsChanged() {
        // enable the login button if both email and password are not empty
        Log.i("FragLoginViewModel", "onCredentialsChanged: ${email.value} ${password.value}")
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