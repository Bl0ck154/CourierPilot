package com.block154.courierpilot

import java.net.HttpURLConnection
import java.net.URL

internal data class ValhallaEndpointConfig(
    val baseUrl: String,
    val bearerToken: String? = null,
    val connectTimeoutMs: Int = 4_000,
    val readTimeoutMs: Int = 6_000,
) {
    init {
        require(baseUrl.startsWith("https://")) { "Production-style Valhalla endpoint must use HTTPS" }
        require(connectTimeoutMs in 500..30_000)
        require(readTimeoutMs in 500..60_000)
        require(bearerToken?.contains('\n') != true && bearerToken?.contains('\r') != true)
    }

    fun routeUrl(): String = baseUrl.trimEnd('/') + "/route"
}

internal class ValhallaHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

/**
 * Ready-to-wire HTTP implementation for the existing RouteProvider boundary.
 *
 * This class is intentionally unused in production for now and the manifest still has no INTERNET
 * permission. When activated later it must run off the Android main thread and remain fail-open with
 * respect to offer capture/persistence.
 */
internal class ValhallaHttpRouteProvider(
    private val config: ValhallaEndpointConfig,
) : RouteProvider {

    override fun route(request: RouteRequest): Result<RouteResult> = runCatching {
        RouteIntelligencePolicy.validate(request)
        val payload = ValhallaContract.buildRoutePayload(request)
        val connection = (URL(config.routeUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            config.bearerToken?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val diagnostic = body.take(800).ifBlank { connection.responseMessage.orEmpty() }
                throw ValhallaHttpException(status, "Valhalla HTTP $status: $diagnostic")
            }
            require(body.isNotBlank()) { "Valhalla returned an empty response" }
            ValhallaContract.parseRouteResponse(request.profile, body)
        } finally {
            connection.disconnect()
        }
    }
}
