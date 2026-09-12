package app.aoide.data

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

/** The one HTTP client the whole app shares, and the name it gives itself. */
object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    const val UA = "Aoide/0.1 (Android)"

    /** Forget every remembered catalogue page, so the next screen asks the service again. */
    fun clearCache() = Music.clearCache()
}
