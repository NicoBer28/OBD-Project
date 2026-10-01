package com.example.obd_app

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Los defaults de OkHttp son 10 s, y el backend tarda 20-25 s en responder cuando
    // arranca en frío. Mismos márgenes que usa el lado Dart (ApiConfig: 45 s).
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cached: Pair<String, ObdApiService>? = null

    /**
     * La URL base la manda Flutter (ApiConfig / --dart-define=OBD_API_BASE_URL), así hay
     * una sola fuente de verdad. Se reconstruye Retrofit solo si la URL cambió.
     */
    fun service(baseUrl: String): ObdApiService {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        cached?.let { (cachedUrl, svc) -> if (cachedUrl == url) return svc }

        synchronized(this) {
            cached?.let { (cachedUrl, svc) -> if (cachedUrl == url) return svc }
            val svc = Retrofit.Builder()
                .baseUrl(url)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ObdApiService::class.java)
            cached = url to svc
            return svc
        }
    }
}
