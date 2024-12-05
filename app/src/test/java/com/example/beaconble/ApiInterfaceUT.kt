package com.example.beaconble

import com.example.beaconble.APIService
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.BeforeClass
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiInterfaceUT {
    companion object {
        const val endpoint = "http://127.0.0.1:5000/"
        lateinit var apiService: APIService
        val user = ApiUserSession("test", "example@example.example", "whateverHash", "whateverSalt")

        @BeforeClass
        @JvmStatic
        fun setup() {
            val retrofit = Retrofit.Builder()
                .baseUrl(endpoint)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            apiService = retrofit.create(APIService::class.java)
        }
    }

    @Test
    fun createUser() {
        val request = user.registerRequest()

        try {
            runBlocking {
                val response = apiService.registerUser(request)
            }
        } catch (e: HttpException) {
            if (e.code() == 409) {
                println("User already exists")  // This is a valid response
            } else {
                throw e
            }
        }
    }

    @Test
    fun loginUser() {
        // login user normally
        val request = user.loginRequest()

        runBlocking {
            val response = apiService.loginUser(request)
            assert(response.access_token != null)
        }

        // login user with bad password
        val bad_password_user = ApiUserSession(user)
        bad_password_user.passHash = "badHash"

        val bad_password_request = bad_password_user.loginRequest()

        runBlocking {
            try {
                val response = apiService.loginUser(bad_password_request)
                assert(false)
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    println("Bad password")  // This is a valid response
                } else {
                    throw e
                }
            }
        }

        // login user with bad username
        val bad_username_user = ApiUserSession(user)
        bad_username_user.username = "badUsername"

        val bad_username_request = bad_username_user.loginRequest()

        runBlocking {
            try {
                val response = apiService.loginUser(bad_username_request)
                assert(false)
            } catch (e: HttpException) {
                assert(e.code() == 404)
            }
        }
    }

    @Test
    fun getSalt() {
        val request = user.username!!

        runBlocking {
            val response = apiService.getUserSalt(request)
            assert(response.passSalt != null)
        }
    }
}