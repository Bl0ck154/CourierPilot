package com.block154.courierpilot

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class RouteEndpointConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val bearerToken: String,
) {
    fun validated(): RouteEndpointConfig {
        require(enabled) { "Route intelligence is disabled" }
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalizedUrl) }
            .getOrElse { throw IllegalArgumentException("Route endpoint is not a valid URL") }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Route endpoint must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Route endpoint must include a host" }
        require(uri.rawUserInfo == null) { "Credentials are not allowed in the route endpoint URL" }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Route endpoint cannot contain a query or fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "Route endpoint must not contain a path"
        }

        val normalizedToken = bearerToken.trim()
        require(normalizedToken.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) {
            "Bearer token must contain $MIN_TOKEN_LENGTH..$MAX_TOKEN_LENGTH characters"
        }
        require(!normalizedToken.contains('\r') && !normalizedToken.contains('\n')) {
            "Bearer token contains invalid characters"
        }
        return copy(baseUrl = normalizedUrl, bearerToken = normalizedToken)
    }

    fun routeUrl(): String = "${validated().baseUrl}/route"

    override fun toString(): String =
        "RouteEndpointConfig(enabled=$enabled, baseUrl=$baseUrl, bearerToken=<redacted>)"

    companion object {
        const val DEFAULT_BASE_URL = "https://valhalla.zivkr.pp.ua"
        private const val MIN_TOKEN_LENGTH = 16
        private const val MAX_TOKEN_LENGTH = 512
    }
}

/**
 * Stores research endpoint configuration in app-private storage excluded from Android backup.
 * The bearer token must never be copied into BuildConfig, logs, diagnostics or the repository.
 */
internal object RouteEndpointSettings {
    private const val FILE_NAME = "route-endpoint.json"

    fun load(context: Context): RouteEndpointConfig {
        val file = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))
        if (!file.baseFile.exists()) return defaults()
        return runCatching {
            val root = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            RouteEndpointConfig(
                enabled = root.optBoolean("enabled", false),
                baseUrl = root.optString("base_url", RouteEndpointConfig.DEFAULT_BASE_URL),
                bearerToken = root.optString("bearer_token", ""),
            )
        }.getOrElse { defaults() }
    }

    fun save(context: Context, config: RouteEndpointConfig) {
        val normalized = if (config.enabled) config.validated() else config.copy(
            baseUrl = config.baseUrl.trim().ifBlank { RouteEndpointConfig.DEFAULT_BASE_URL },
            bearerToken = config.bearerToken.trim(),
        )
        val root = JSONObject()
            .put("enabled", normalized.enabled)
            .put("base_url", normalized.baseUrl)
            .put("bearer_token", normalized.bearerToken)
        val atomicFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))
        val stream = atomicFile.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (failure: Throwable) {
            atomicFile.failWrite(stream)
            throw failure
        }
    }

    fun clear(context: Context) {
        AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME)).delete()
    }

    private fun defaults() = RouteEndpointConfig(
        enabled = false,
        baseUrl = RouteEndpointConfig.DEFAULT_BASE_URL,
        bearerToken = "",
    )
}

internal data class RouteHttpResponse(
    val status: Int,
    val body: String,
)

internal interface ValhallaHttpTransport {
    fun postJson(url: String, authorization: String, body: String): RouteHttpResponse
}

internal class HttpsValhallaTransport : ValhallaHttpTransport {
    override fun postJson(url: String, authorization: String, body: String): RouteHttpResponse {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IOException("Route endpoint did not create an HTTPS connection")
        val requestBytes = body.toByteArray(Charsets.UTF_8)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { it.write(requestBytes) }

            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            RouteHttpResponse(status, responseStream.readUtf8Bounded(MAX_RESPONSE_BYTES))
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream?.readUtf8Bounded(limit: Int): String {
        if (this == null) return ""
        return use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("Valhalla response exceeded $limit bytes")
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

internal class ValhallaHttpException(
    val status: Int,
    message: String,
) : IOException("Valhalla HTTP $status: $message")

internal class ValhallaRouteProvider(
    private val config: RouteEndpointConfig,
    private val transport: ValhallaHttpTransport = HttpsValhallaTransport(),
) : RouteProvider {
    override fun route(request: RouteRequest): Result<RouteResult> = runCatching {
        val endpoint = config.validated()
        val response = transport.postJson(
            url = "${endpoint.baseUrl}/route",
            authorization = "Bearer ${endpoint.bearerToken}",
            body = ValhallaContract.buildRoutePayload(request),
        )
        if (response.status !in 200..299) {
            throw ValhallaHttpException(response.status, ValhallaContract.parseErrorMessage(response.body))
        }
        ValhallaContract.parseRouteResponse(request.profile, response.body, response.status)
    }
}
