package com.block154.courierpilot

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ValhallaRouteProviderTest {

    private lateinit var application: Application
    private val token = "research-token-that-is-never-committed"
    private val request = RouteRequest(
        points = listOf(RoutePoint(54.6872, 25.2797), RoutePoint(54.7005, 25.3030)),
        profile = RouteProfile.PEDESTRIAN_SHORTCUT,
    )

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        RouteEndpointSettings.clear(application)
    }

    @After
    fun tearDown() {
        RouteEndpointSettings.clear(application)
    }

    @Test
    fun providerPostsAuthenticatedPayloadAndPreservesHttpProvenance() {
        val transport = RecordingTransport(
            RouteHttpResponse(
                200,
                """{"trip":{"summary":{"length":2.578,"time":1851.316},"legs":[{"shape":"polyline"}]}}""",
            )
        )
        val provider = ValhallaRouteProvider(enabledConfig(), transport)

        val result = provider.route(request).getOrThrow()

        assertEquals("https://valhalla.zivkr.pp.ua/route", transport.url)
        assertEquals("Bearer $token", transport.authorization)
        assertEquals("pedestrian", JSONObject(transport.body).getString("costing"))
        assertEquals(200, result.httpStatus)
        assertEquals(2578, result.distanceMeters)
        assertEquals(listOf("polyline"), result.legShapes)
    }

    @Test
    fun nonSuccessResponseIsReturnedAsFailureWithoutExposingToken() {
        val transport = RecordingTransport(RouteHttpResponse(401, """{"error":"Unauthorized"}"""))
        val failure = ValhallaRouteProvider(enabledConfig(), transport).route(request).exceptionOrNull()

        assertTrue(failure is ValhallaHttpException)
        val httpFailure = failure as ValhallaHttpException
        assertEquals(401, httpFailure.status)
        assertFalse(httpFailure.message.orEmpty().contains(token))
    }

    @Test
    fun disabledConfigurationFailsBeforeNetworkCall() {
        val transport = RecordingTransport(RouteHttpResponse(500, ""))
        val result = ValhallaRouteProvider(enabledConfig().copy(enabled = false), transport).route(request)

        assertTrue(result.isFailure)
        assertFalse(transport.called)
    }

    @Test
    fun endpointRequiresHttpsAndRejectsHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            enabledConfig().copy(baseUrl = "http://valhalla.zivkr.pp.ua").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            enabledConfig().copy(bearerToken = "$token\nInjected: value").validated()
        }
    }

    @Test
    fun endpointSettingsRoundTripInPrivateNoBackupStorageAndRedactToString() {
        RouteEndpointSettings.save(application, enabledConfig())

        assertEquals(enabledConfig(), RouteEndpointSettings.load(application))
        assertTrue(application.noBackupFilesDir.resolve("route-endpoint.json").isFile)
        assertFalse(enabledConfig().toString().contains(token))
    }

    private fun enabledConfig() = RouteEndpointConfig(
        enabled = true,
        baseUrl = "https://valhalla.zivkr.pp.ua",
        bearerToken = token,
    )

    private class RecordingTransport(
        private val response: RouteHttpResponse,
    ) : ValhallaHttpTransport {
        var called = false
        var url = ""
        var authorization = ""
        var body = ""

        override fun postJson(url: String, authorization: String, body: String): RouteHttpResponse {
            called = true
            this.url = url
            this.authorization = authorization
            this.body = body
            return response
        }
    }
}
