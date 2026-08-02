package com.feldman.ha.api

import com.feldman.ha.BuildConfig
import kotlinx.coroutines.runBlocking
import retrofit2.http.GET
import okhttp3.logging.HttpLoggingInterceptor

data class FrigateConfig(
    val cameras: Map<String, CameraConfig>
)

data class CameraConfig(
    val name: String
)

data class FrigateEvent(
    val id: String,
    val camera: String,
    val label: String,
    val start_time: Double,
    val end_time: Double?,
    val top_score: Double?,
    val thumbnail: String? = null
)

data class FrigateRecordingDay(
    val day: String,
    val hours: List<FrigateRecordingHour>
)

data class FrigateRecordingHour(
    val hour: Int,
    val duration: Int,
    val events: Int,
    val motion: Int = 0,
    val objects: Int = 0
)

interface FrigateApi {
    @GET("api/config")
    suspend fun getConfig(): FrigateConfig

    @GET("api/events")
    suspend fun getEvents(
        @retrofit2.http.Query("camera") camera: String,
        @retrofit2.http.Query("limit") limit: Int = 20
    ): List<FrigateEvent>

    @GET("api/{camera}/recordings/summary")
    suspend fun getRecordingSummary(
        @retrofit2.http.Path("camera") camera: String,
        @retrofit2.http.Query("timezone") timezone: String? = null
    ): List<FrigateRecordingDay>
}

val demoFrigateApi = object : FrigateApi {
    override suspend fun getConfig(): FrigateConfig = FrigateConfig(
        cameras = mapOf(
            "front_door" to CameraConfig("Front Door"),
            "backyard" to CameraConfig("Backyard"),
            "living_room" to CameraConfig("Living Room"),
            "garage" to CameraConfig("Garage")
        )
    )

    override suspend fun getEvents(camera: String, limit: Int): List<FrigateEvent> {
        val now = (System.currentTimeMillis() / 1000).toDouble()
        val thumb = getFrigateCameraSnapshotUrl("demo", camera, 0)
        return listOf(
            FrigateEvent(
                id = "demo_event_1",
                camera = camera,
                label = "person",
                start_time = now - 300,
                end_time = now - 240,
                top_score = 0.94,
                thumbnail = thumb
            ),
            FrigateEvent(
                id = "demo_event_2",
                camera = camera,
                label = "car",
                start_time = now - 3600,
                end_time = now - 3500,
                top_score = 0.89,
                thumbnail = thumb
            ),
            FrigateEvent(
                id = "demo_event_3",
                camera = camera,
                label = "dog",
                start_time = now - 7200,
                end_time = now - 7100,
                top_score = 0.85,
                thumbnail = thumb
            )
        ).take(limit)
    }

    override suspend fun getRecordingSummary(camera: String, timezone: String?): List<FrigateRecordingDay> {
        val todayStr = java.time.LocalDate.now().toString()
        return listOf(
            FrigateRecordingDay(
                day = todayStr,
                hours = (0..23).map { h ->
                    FrigateRecordingHour(
                        hour = h,
                        duration = 3600,
                        events = if (h in 8..20) (h % 4 + 1) else 0,
                        motion = if (h in 8..20) (h * 15 + 30) else 10,
                        objects = if (h in 8..20) (h % 3 + 1) else 0
                    )
                }
            )
        )
    }
}

fun getFrigateCameraSnapshotUrl(baseUrl: String, cameraName: String, timestamp: Long): String {
    val url = getFrigateApiUrl(baseUrl)
    if (url.contains("demo") || cameraName in listOf("front_door", "backyard", "living_room", "garage")) {
        return "android.resource://com.feldman.ha/drawable/demo_camera_placeholder"
    }
    return "${url}api/$cameraName/latest.jpg?t=$timestamp"
}

fun getFrigateApiUrl(baseUrl: String): String {
    var url = baseUrl.trim()
    if (url.isBlank() || url.equals("demo", ignoreCase = true) || url.startsWith("demo", ignoreCase = true)) {
        return "http://demo/frigate/"
    }
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "http://$url"
    }
    // Auto-convert Nabu Casa UI URLs to API Proxy URLs if needed
    if (url.contains(".ui.nabu.casa/app/")) {
        url = url.replace("/app/", "/api/hassio/addons/")
        if (!url.endsWith("/")) url += "/"
        url = "${url}proxy/"
    }
    if (!url.endsWith("/")) url += "/"
    return url
}

fun provideFrigateApi(token: String, baseUrl: String): FrigateApi {
    return provideFrigateApi(tokenProvider = { token }, baseUrl = baseUrl)
}

fun provideFrigateApi(tokenProvider: suspend () -> String, baseUrl: String): FrigateApi {
    val url = getFrigateApiUrl(baseUrl)
    if (url.contains("demo")) return demoFrigateApi

    val logging = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    val client = okhttp3.OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor { chain ->
            val token = runBlocking { tokenProvider() }
            chain.proceed(
                chain.request()
                    .newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            )
        }
        .build()

    val gson = com.google.gson.GsonBuilder()
        .setLenient()
        .create()

    return try {
        retrofit2.Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create(gson))
            .client(client)
            .build()
            .create(FrigateApi::class.java)
    } catch (e: Throwable) {
        android.util.Log.e("FrigateApi", "Failed to create Frigate client for $url: ${e.message}, returning demoFrigateApi", e)
        demoFrigateApi
    }
}
