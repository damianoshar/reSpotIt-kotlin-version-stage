package com.spotifydashboard.app.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object SpotifyApiClient {
    private const val BASE_URL = "https://api.spotify.com/v1/"
    private const val MAX_RETRIES = 2

    /**
     * Mirrors TS spotifyFetch(): on HTTP 429, honour Retry-After and retry.
     */
    private val rateLimitInterceptor = Interceptor { chain ->
        var response = chain.proceed(chain.request())
        var retries = 0
        while (response.code == 429 && retries < MAX_RETRIES) {
            val retryAfterSecs = response.header("Retry-After")?.toLongOrNull() ?: 2L
            response.close()
            try {
                Thread.sleep(retryAfterSecs * 1000)
            } catch (_: InterruptedException) {
                break
            }
            response = chain.proceed(chain.request())
            retries++
        }
        response
    }

    private val logging = HttpLoggingInterceptor().apply {
        // BASIC only: BODY logs every ~1 MB page of /me/albums to logcat,
        // which is very expensive on low-end devices (Android 9 tablet).
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        // Spotify calls can stall on low-end devices or slow links; the default
        // 10s read timeout is too tight for a full album sync.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(rateLimitInterceptor)
        .addInterceptor(logging)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: SpotifyApiService by lazy {
        retrofit.create(SpotifyApiService::class.java)
    }
    val playlistService: SpotifyPlaylistApiService by lazy {
        retrofit.create(SpotifyPlaylistApiService::class.java)
    }
}
