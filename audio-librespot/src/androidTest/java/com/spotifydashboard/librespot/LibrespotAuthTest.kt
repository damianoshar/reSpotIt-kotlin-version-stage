package com.spotifydashboard.librespot

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * M4 auth verification without a Spotify account: PKCE vectors, authorize URL,
 * the loopback redirect listener (simulated browser), and the token exchange
 * against a local fake endpoint. The real browser/Spotify round-trip is manual
 * (see PROGRESS.md).
 */
@RunWith(AndroidJUnit4::class)
class LibrespotAuthTest {

    @Test
    fun pkceMatchesRfc7636Vector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierAndStateAreHighEntropy() {
        val first = Pkce.newVerifier()
        val second = Pkce.newVerifier()
        assertTrue("verifier shorter than 43 chars: $first", first.length >= 43)
        assertTrue("verifiers repeat", first != second)
        assertTrue("challenges repeat", Pkce.challenge(first) != Pkce.challenge(second))
        assertTrue("state is not 32 hex chars", Pkce.newState().matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun authorizeUrlHasPkceParameters() {
        val url = LibrespotAuth.buildAuthorizeUrl(
            "http://127.0.0.1:8898/login",
            "state-1",
            "challenge-1",
        )
        assertTrue(url.startsWith(LibrespotAuth.AUTHORIZE_ENDPOINT + "?"))

        val params = Uri.parse(url)
        assertEquals("code", params.getQueryParameter("response_type"))
        assertEquals(LibrespotAuth.CLIENT_ID, params.getQueryParameter("client_id"))
        assertEquals("streaming", params.getQueryParameter("scope"))
        assertEquals("http://127.0.0.1:8898/login", params.getQueryParameter("redirect_uri"))
        assertEquals("state-1", params.getQueryParameter("state"))
        assertEquals("challenge-1", params.getQueryParameter("code_challenge"))
        assertEquals("S256", params.getQueryParameter("code_challenge_method"))
    }

    @Test
    fun loopbackFlowReceivesCallbackAndReturnsToken() {
        val redirect = AtomicReference<String>()
        var exchangedVerifier: String? = null

        val token = LibrespotAuth.authorize(
            port = 0,
            timeoutMs = 10_000,
            launchBrowser = { url ->
                val uri = Uri.parse(url)
                val state = uri.getQueryParameter("state")!!
                val target = uri.getQueryParameter("redirect_uri")!!
                redirect.set(target)
                Thread { httpGet("$target?code=test-code&state=$state") }
                    .apply { isDaemon = true }
                    .start()
            },
            exchange = { code, verifier, redirectUri ->
                assertEquals("test-code", code)
                assertEquals(redirect.get(), redirectUri)
                exchangedVerifier = verifier
                "test-token"
            },
        )

        assertEquals("test-token", token)
        assertTrue(redirect.get()!!.startsWith("http://127.0.0.1:"))
        assertTrue("verifier too short", (exchangedVerifier?.length ?: 0) >= 43)
    }

    @Test
    fun stateMismatchIsRejected() {
        try {
            LibrespotAuth.authorize(
                port = 0,
                timeoutMs = 5_000,
                launchBrowser = { url ->
                    val target = Uri.parse(url).getQueryParameter("redirect_uri")!!
                    Thread { httpGet("$target?code=test-code&state=wrong") }
                        .apply { isDaemon = true }
                        .start()
                },
                exchange = { _, _, _ -> "unreachable" },
            )
            fail("state mismatch was accepted")
        } catch (e: LibrespotAuthException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("state"))
        }
    }

    @Test
    fun denialIsReported() {
        try {
            LibrespotAuth.authorize(
                port = 0,
                timeoutMs = 5_000,
                launchBrowser = { url ->
                    val target = Uri.parse(url).getQueryParameter("redirect_uri")!!
                    Thread { httpGet("$target?error=access_denied") }
                        .apply { isDaemon = true }
                        .start()
                },
                exchange = { _, _, _ -> "unreachable" },
            )
            fail("denial was accepted")
        } catch (e: LibrespotAuthException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("access_denied"))
        }
    }

    @Test
    fun timeoutIsReported() {
        try {
            LibrespotAuth.authorize(
                port = 0,
                timeoutMs = 300,
                launchBrowser = { },
                exchange = { _, _, _ -> "unreachable" },
            )
            fail("timeout was not reported")
        } catch (e: LibrespotAuthException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("Timed out"))
        }
    }

    @Test
    fun tokenExchangeParsesAccessToken() {
        val server = FakeHttpServer(
            200,
            """{"access_token":"fake-token","token_type":"Bearer","expires_in":3600}""",
        )
        try {
            val token = LibrespotTokenExchange.exchange(
                code = "the-code",
                verifier = "the-verifier",
                redirectUri = "http://127.0.0.1:8898/login",
                endpoint = server.endpoint,
            )
            assertEquals("fake-token", token)
            val body = server.requestBody
            assertTrue("body=$body", body.contains("grant_type=authorization_code"))
            assertTrue("body=$body", body.contains("code=the-code"))
            assertTrue("body=$body", body.contains("code_verifier=the-verifier"))
            assertTrue("body=$body", body.contains("client_id=${LibrespotAuth.CLIENT_ID}"))
        } finally {
            server.close()
        }
    }

    @Test
    fun tokenExchangeErrorIsReported() {
        val server = FakeHttpServer(
            400,
            """{"error":"invalid_grant","error_description":"Invalid authorization code"}""",
        )
        try {
            LibrespotTokenExchange.exchange(
                "code",
                "verifier",
                "http://127.0.0.1:8898/login",
                server.endpoint,
            )
            fail("error response was accepted")
        } catch (e: LibrespotAuthException) {
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("invalid_grant"))
            assertTrue("unexpected message: ${e.message}", e.message!!.contains("Invalid authorization code"))
        } finally {
            server.close()
        }
    }

    private fun httpGet(url: String) {
        runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 5_000
                try {
                    inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            }
        }
    }

    /** Minimal loopback HTTP server that captures the request body. */
    private class FakeHttpServer(status: Int, responseBody: String) {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val endpoint: String = "http://127.0.0.1:${server.localPort}/token"

        @Volatile
        var requestBody: String = ""
            private set

        private val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val headerBytes = java.io.ByteArrayOutputStream()
                    var matched = 0
                    while (matched < 4) {
                        val byte = input.read()
                        if (byte < 0) break
                        headerBytes.write(byte)
                        matched = when {
                            matched == 0 && byte == '\r'.code -> 1
                            matched == 1 && byte == '\n'.code -> 2
                            matched == 2 && byte == '\r'.code -> 3
                            matched == 3 && byte == '\n'.code -> 4
                            byte == '\r'.code -> 1
                            else -> 0
                        }
                    }

                    val headers = headerBytes.toString("US-ASCII")
                    val contentLength = headers.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = input.read(bodyBytes, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    requestBody = String(bodyBytes, 0, read, Charsets.UTF_8)

                    val body = responseBody.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val out = socket.getOutputStream()
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    out.write(body)
                    out.flush()
                }
            }
        }.apply { isDaemon = true }

        init {
            thread.start()
        }

        fun close() {
            runCatching { server.close() }
            thread.join(2_000)
        }
    }
}
