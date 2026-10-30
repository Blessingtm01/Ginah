package zw.co.accisafe.rdc.data.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import zw.co.accisafe.rdc.BuildConfig
import zw.co.accisafe.rdc.data.SessionStore
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
val appJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
    coerceInputValues = true
}

/** Thrown for problems the user can act on; [message] is ready to show. */
class ApiProblem(message: String, val httpCode: Int? = null, val offline: Boolean = false) : Exception(message)

/** Converts network and HTTP failures into a readable [ApiProblem]. */
fun Throwable.toProblem(): ApiProblem = when (this) {
    is ApiProblem -> this
    is HttpException -> {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val detail = body?.let { runCatching { appJson.parseToJsonElement(it).jsonObject["detail"] }.getOrNull() }
        val text = when (detail) {
            is JsonPrimitive -> detail.content
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.content
            else -> null
        } ?: when (code()) {
            401 -> "Your session has expired. Please sign in again."
            403 -> "You do not have permission for this action."
            404 -> "Not found, or you do not have access to it."
            in 500..599 -> "The server had a problem. Try again shortly."
            else -> "Request failed (HTTP ${code()})."
        }
        ApiProblem(text, code())
    }
    is IOException -> ApiProblem("No connection to the council server. Check your network.", offline = true)
    else -> ApiProblem(message ?: "Unexpected error")
}

/**
 * Builds the Retrofit service for the configured server address. Access tokens are attached
 * automatically and refreshed once on HTTP 401; if refresh fails the session ends.
 */
@Singleton
class ApiClient @Inject constructor(private val session: SessionStore) {

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut: SharedFlow<Unit> = _signedOut

    private var cachedUrl: String? = null
    private var cached: ApiService? = null

    val api: ApiService
        @Synchronized get() {
            val url = session.serverUrl
            if (url != cachedUrl || cached == null) {
                cached = build(url)
                cachedUrl = url
            }
            return cached!!
        }

    private val authHeader = Interceptor { chain ->
        val builder = chain.request().newBuilder()
            .header("X-Request-ID", UUID.randomUUID().toString().replace("-", ""))
            .header("X-Device", "Android AcciSafe RDC ${BuildConfig.VERSION_NAME}")
        session.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
        chain.proceed(builder.build())
    }

    private val refresher = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath.contains("/auth/")) return null
            if (response.priorResponse != null) return null // already retried once
            val refresh = session.refreshToken() ?: return null
            synchronized(this) {
                // Another request may have refreshed while we waited.
                val current = session.accessToken()
                val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
                if (current != null && current != sent) {
                    return response.request.newBuilder().header("Authorization", "Bearer $current").build()
                }
                return runBlocking {
                    try {
                        val t = plainApi(session.serverUrl).refresh(RefreshRequest(refresh))
                        session.saveSession(t.accessToken, t.refreshToken, t.user)
                        response.request.newBuilder().header("Authorization", "Bearer ${t.accessToken}").build()
                    } catch (e: HttpException) {
                        session.clearSession()
                        _signedOut.tryEmit(Unit)
                        null
                    } catch (e: IOException) {
                        null
                    }
                }
            }
        }
    }

    private fun client(withAuth: Boolean) = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { if (withAuth) addInterceptor(authHeader).authenticator(refresher) }
        .build()

    val okHttp: OkHttpClient by lazy { client(withAuth = true) }

    private fun retrofit(url: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(url)
        .client(client)
        .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
        .build()

    private fun build(url: String): ApiService = retrofit(url, okHttp).create(ApiService::class.java)
    private fun plainApi(url: String): ApiService = retrofit(url, client(withAuth = false)).create(ApiService::class.java)

    companion object {
        /** Validates a server address entered by the user. Release builds accept HTTPS only. */
        fun normaliseServerUrl(input: String): String? {
            var url = input.trim()
            if (url.isEmpty()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            if (url.startsWith("http://") && !BuildConfig.ALLOW_HTTP) return null
            if (!url.endsWith("/")) url += "/"
            return url.toHttpUrlOrNull()?.let { url }
        }
    }
}
