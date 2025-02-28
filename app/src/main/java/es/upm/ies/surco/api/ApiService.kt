package es.upm.ies.surco.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part


const val APIv1_base = "api/v1/"

interface APIService {
    @GET(APIv1_base + "isUp")
    suspend fun isUp(): ResponseBody

    @GET(APIv1_base + "salt")
    @Headers("Content-type: application/json")
    suspend fun getUserSalt(
        @Header("email") email: String,
    ): ApiUserSession.SaltResponse

    @POST(APIv1_base + "register")
    @Headers("Content-type: application/json")
    suspend fun registerUser(
        @Body request: ApiUserSession.RegisterRequest,
    ): ResponseBody

    @POST(APIv1_base + "login")
    @Headers("Content-type: application/json")
    suspend fun loginUser(
        @Body request: ApiUserSession.LoginRequest,
    ): ApiUserSession.LoginResponse

    @Multipart
    @POST(APIv1_base + "session/upload")
    suspend fun uploadBeacons(
        @Header("Authorization") token: String,
        @Part file: MultipartBody.Part,
    ): ResponseBody
}
