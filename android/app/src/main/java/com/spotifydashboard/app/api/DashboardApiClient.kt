package com.spotifydashboard.app.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit factory for conformant media servers (/api/v1).
 * Unlike [SpotifyApiClient] (fixed Spotify host), the base URL is
 * user-configurable — normalised to a trailing slash for Retrofit.
 * Emulator note: host loopback from an emulator is http://10.0.2.2:port.
 */
object DashboardApiClient {
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"

    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.isEmpty()) DEFAULT_SERVER_URL else trimmed
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            // A phone → server push sends the whole library in one import
            // request; 10s (the OkHttp default) is too tight for a large
            // library on a slow link. Normal calls are unaffected.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val gson by lazy {
        GsonBuilder()
            .registerTypeAdapter(
                PlaylistListEnvelope::class.java,
                PlaylistListEnvelopeDeserializer()
            )
            .registerTypeAdapter(
                StatsEnvelope::class.java,
                StatsEnvelopeDeserializer()
            )
            .create()
    }

    private val services = mutableMapOf<String, DashboardApiService>()

    @Synchronized
    fun serviceFor(serverUrl: String): DashboardApiService {
        val base = normalizeServerUrl(serverUrl) + "/"
        return services.getOrPut(base) {
            Retrofit.Builder()
                .baseUrl(base)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(DashboardApiService::class.java)
        }
    }
}
