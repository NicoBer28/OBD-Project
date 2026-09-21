package com.example.obd_app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    // TODO: Cambiá esto por la IP local de tu servidor (ej: "http://192.168.1.19:8080") 
    // o el dominio en producción.
    private const val BASE_URL = "https://tu-backend-api.com" 

    val retrofitService: ObdApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ObdApiService::class.java)
    }
}