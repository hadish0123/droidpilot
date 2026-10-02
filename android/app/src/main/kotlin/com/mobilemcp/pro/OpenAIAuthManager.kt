package com.mobilemcp.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID

data class ChatGptProfile(
    val subject: String,
    val email: String?,
    val name: String?,
    val clientId: String,
    val planSharingEnabled: Boolean
)

/**
 * Official Sign in with ChatGPT flow for open-source/local clients.
 *
 * The OAuth callback stays on 127.0.0.1 as required by OpenAI. After the
 * browser reaches the loopback callback, PRIME deep-links back into the app
 * and completes code exchange there.
 */
class OpenAIAuthManager(private val context: Context) {

    companion object {
        private const val AUTHORIZE_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
        private const val TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        private const val JWKS_ENDPOINT = "https://auth.openai.com/.well-known/jwks.json"
        private const val DISCOVERY_ENDPOINT = "https://auth.openai.com/.well-known/openid-configuration"
        private const val ISSUER = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        private const val APP_NAME = "PRIME"
        private const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
        private const val SCOPES =
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        private const val RECORD_KEY = "chatgpt_auth_record"
        private const val PENDING_CLIENT_KEY = "chatgpt_pending_client_id"
        private const val HOST_PREFS = "prime_p6_host"
        private const val HOST_ID_KEY = "ext_agent_host_id"
        private const val APP_RETURN_URI = "primep6://auth-complete"
        // Activity and voice service share rotating credentials. Refreshing
        // them concurrently can invalidate the token saved by the other one.
        private val refreshMutex = Mutex()
        @Volatile private var memoryAccessToken: String? = null
        @Volatile private var memoryAccessTokenExpiresAtMs: Long = 0L
    }

    private data class AuthRecord(
        val subject: String?,
        val email: String?,
        val name: String?,
        val clientId: String?,
        val idToken: String?,
        val accessToken: String?,
        val refreshToken: String?,
        val scopes: Set<String>,
        val expiresAtMs: Long
    )

    private val secureStore = SecureStore(context)
    private val random = SecureRandom()

    private fun loadRecord(): AuthRecord? {
        val raw = secureStore.getString(RECORD_KEY) ?: return null
        return try {
            val json = JSONObject(raw)
            val scopesJson = json.optJSONArray("scopes") ?: JSONArray()
            val scopes = buildSet {
                for (i in 0 until scopesJson.length()) add(scopesJson.optString(i))
            }
            AuthRecord(
                subject = json.optString("subject").takeIf { it.isNotBlank() },
                email = json.optString("email").takeIf { it.isNotBlank() },
                name = json.optString("name").takeIf { it.isNotBlank() },
                clientId = json.optString("client_id").takeIf { it.isNotBlank() },
                idToken = json.optString("id_token").takeIf { it.isNotBlank() },
                accessToken = json.optString("access_token").takeIf { it.isNotBlank() },
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                scopes = scopes,
                expiresAtMs = json.optLong("expires_at_ms", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveRecord(record: AuthRecord) {
        val json = JSONObject()
            .put("subject", record.subject ?: "")
            .put("email", record.email ?: "")
            .put("name", record.name ?: "")
            .put("client_id", record.clientId ?: "")
            .put("id_token", record.idToken ?: "")
            .put("access_token", record.accessToken ?: "")
            .put("refresh_token", record.refreshToken ?: "")
            .put("expires_at_ms", record.expiresAtMs)

        val scopes = JSONArray()
        record.scopes.forEach { scopes.put(it) }
        json.put("scopes", scopes)
        secureStore.putString(RECORD_KEY, json.toString())
    }

    private fun pendingClientId(): String? =
        secureStore.getString(PENDING_CLIENT_KEY)?.takeIf { it.startsWith("oaiapp_") }

    private fun savePendingClientId(clientId: String) {
        secureStore.putString(PENDING_CLIENT_KEY, clientId)
    }

    private fun clearPendingClientId() {
        secureStore.remove(PENDING_CLIENT_KEY)
    }

    private fun hostId(): String {
        val prefs = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(HOST_ID_KEY, null)
        if (!existing.isNullOrBlank()) return existing

        val created = "urn:uuid:" + UUID.randomUUID().toString()
        prefs.edit().putString(HOST_ID_KEY, created).apply()
        return created
    }

    fun currentProfile(): ChatGptProfile? {
        val r = loadRecord() ?: return null
        val subject = r.subject ?: return null
        val clientId = r.clientId ?: return null
        return ChatGptProfile(
            subject = subject,
            email = r.email,
            name = r.name,
            clientId = clientId,
            planSharingEnabled = r.scopes.contains(REQUIRED_SCOPE)
        )
    }

    fun isSignedIn(): Boolean {
        val r = loadRecord() ?: return false
        return !r.accessToken.isNullOrBlank() &&
            !r.refreshToken.isNullOrBlank() &&
            r.scopes.contains(REQUIRED_SCOPE)
    }

    suspend fun testOpenAiConnection(): String = withContext(Dispatchers.IO) {
        val discovery = getJsonWithRetry(DISCOVERY_ENDPOINT, attempts = 2)
        val issuer = discovery.optString("issuer").ifBlank { ISSUER }
        "OpenAI reachable • " + issuer
    }

    suspend fun signIn(
        openBrowser: (Uri) -> Unit,
        onCallbackReceived: (() -> Unit)? = null
    ): ChatGptProfile = withContext(Dispatchers.IO) {
        // Verify that PRIME itself can reach OpenAI before sending the user
        // into the browser. This avoids a successful browser approval followed
        // by a confusing in-app DNS failure on split-tunnel VPN setups.
        getJsonWithRetry(DISCOVERY_ENDPOINT, attempts = 2)

        val previous = loadRecord()
        val savedClientId = previous?.clientId?.takeIf { it.startsWith("oaiapp_") }
            ?: pendingClientId()
        val requestClientId = savedClientId ?: DYNAMIC_CLIENT_ID

        val state = randomUrlSafe(32)
        val nonce = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        server.soTimeout = 180_000
        val redirectUri = "http://127.0.0.1:" + server.localPort + "/auth/callback"

        val builder = Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", requestClientId)
            .appendQueryParameter("ext_agent_host_id", hostId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("resource", RESOURCE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)

        if (savedClientId == null) {
            builder.appendQueryParameter("agent_name_hint", APP_NAME)
        } else {
            previous?.idToken?.takeIf { it.isNotBlank() }?.let {
                builder.appendQueryParameter("id_token_hint", it)
            }
            previous?.email?.takeIf { it.isNotBlank() }?.let {
                builder.appendQueryParameter("login_hint", it)
            }
        }

        withContext(Dispatchers.Main) {
            openBrowser(builder.build())
        }

        val callback = try {
            val socket = server.accept()
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine()
                    ?: throw IllegalStateException("Empty OAuth callback")
                val target = requestLine.split(" ").getOrNull(1)
                    ?: throw IllegalStateException("Invalid OAuth callback")
                val callbackUri = URI("http://127.0.0.1" + target)
                val params = parseQuery(callbackUri.rawQuery.orEmpty())

                val ok = params["state"] == state && params["error"].isNullOrBlank()
                val body = if (ok) {
                    """
                    <html>
                      <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1" />
                        <meta http-equiv="refresh" content="0;url=$APP_RETURN_URI" />
                      </head>
                      <body style="font-family:sans-serif;background:#070A12;color:#fff;padding:36px;text-align:center">
                        <h2>PRIME authorization received</h2>
                        <p>Returning to PRIME to finish the connection.</p>
                        <p><a style="color:#7C5CFF" href="$APP_RETURN_URI">Return to PRIME</a></p>
                      </body>
                    </html>
                    """.trimIndent()
                } else {
                    """
                    <html>
                      <body style="font-family:sans-serif;padding:36px;text-align:center">
                        <h2>PRIME sign-in was not completed</h2>
                        <p>Return to PRIME and try again.</p>
                        <p><a href="$APP_RETURN_URI">Return to PRIME</a></p>
                      </body>
                    </html>
                    """.trimIndent()
                }

                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = it.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Content-Length: " + bytes.size + "\r\n" +
                        "Connection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                output.write(bytes)
                output.flush()
                params
            }
        } finally {
            server.close()
        }

        withContext(Dispatchers.Main) {
            onCallbackReceived?.invoke()
        }

        if (callback["state"] != state) throw IllegalStateException("OAuth state mismatch")
        callback["error"]?.let { error ->
            throw IllegalStateException("ChatGPT authorization was not completed: " + error)
        }

        val code = callback["code"] ?: throw IllegalStateException("Authorization code missing")
        val callbackClientId = callback["client_id"]

        val issuedClientId = if (savedClientId == null) {
            callbackClientId?.takeIf { it.startsWith("oaiapp_") }
                ?: throw IllegalStateException("OpenAI did not return an issued client ID")
        } else {
            if (!callbackClientId.isNullOrBlank() && callbackClientId != savedClientId) {
                throw IllegalStateException("Returned client ID does not match the saved registration")
            }
            savedClientId
        }

        // Keep the issued dynamic registration even if the network disappears
        // between browser approval and token exchange.
        savePendingClientId(issuedClientId)

        val tokenJson = postFormWithRetry(
            TOKEN_ENDPOINT,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to issuedClientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
                "resource" to RESOURCE
            )
        )

        val idToken = tokenJson.optString("id_token")
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ID token missing")
        val claims = validateIdToken(idToken, issuedClientId, nonce)
        val subject = claims.optString("sub").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ID token subject missing")

        if (previous?.subject != null && previous.subject != subject) {
            throw IllegalStateException("Signed-in ChatGPT account does not match this registration")
        }

        val grantedScopes = parseScopes(tokenJson.optString("scope"))
        if (!grantedScopes.contains(REQUIRED_SCOPE)) {
            throw IllegalStateException("ChatGPT plan usage permission was not granted")
        }

        val accessToken = tokenJson.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Access token missing")
        val refreshToken = tokenJson.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Refresh token missing")
        val expiresIn = tokenJson.optLong("expires_in", 3600L)

        val record = AuthRecord(
            subject = subject,
            email = claims.optString("email").takeIf { it.isNotBlank() },
            name = claims.optString("name").takeIf { it.isNotBlank() }
                ?: claims.optString("preferred_username").takeIf { it.isNotBlank() },
            clientId = issuedClientId,
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            scopes = grantedScopes,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
        )
        saveRecord(record)
        memoryAccessToken = accessToken
        memoryAccessTokenExpiresAtMs = record.expiresAtMs
        clearPendingClientId()

        ChatGptProfile(
            subject = subject,
            email = record.email,
            name = record.name,
            clientId = issuedClientId,
            planSharingEnabled = true
        )
    }

    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val inMemory = memoryAccessToken
        if (
            !inMemory.isNullOrBlank() &&
            memoryAccessTokenExpiresAtMs - now > 120_000L
        ) {
            return@withContext inMemory
        }

        refreshMutex.withLock {
            val secondMemoryCheck = memoryAccessToken
            if (
                !secondMemoryCheck.isNullOrBlank() &&
                memoryAccessTokenExpiresAtMs -
                    System.currentTimeMillis() > 120_000L
            ) {
                return@withLock secondMemoryCheck
            }

            val current = loadRecord()
                ?: throw IllegalStateException("Continue with ChatGPT first")
            val access = current.accessToken
            if (!access.isNullOrBlank() &&
                current.expiresAtMs - System.currentTimeMillis() > 120_000L
            ) {
                memoryAccessToken = access
                memoryAccessTokenExpiresAtMs = current.expiresAtMs
                return@withLock access
            }

            val refresh = current.refreshToken
                ?: throw IllegalStateException("ChatGPT session needs to be connected again")
            val clientId = current.clientId
                ?: throw IllegalStateException("Saved ChatGPT registration is incomplete")

            val tokenJson = postFormWithRetry(
                TOKEN_ENDPOINT,
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refresh,
                    "resource" to RESOURCE
                )
            )

            val newAccess = tokenJson.optString("access_token").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("OpenAI refresh did not return an access token")
            val newRefresh = tokenJson.optString("refresh_token").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("OpenAI refresh did not return a replacement refresh token")
            val newIdToken = tokenJson.optString("id_token").takeIf { it.isNotBlank() }
                ?: current.idToken
            val scopes = parseScopes(tokenJson.optString("scope")).ifEmpty { current.scopes }
            if (!scopes.contains(REQUIRED_SCOPE)) {
                throw IllegalStateException("ChatGPT plan usage is no longer enabled for PRIME")
            }

            val updated = current.copy(
                idToken = newIdToken,
                accessToken = newAccess,
                refreshToken = newRefresh,
                scopes = scopes,
                expiresAtMs = System.currentTimeMillis() +
                    tokenJson.optLong("expires_in", 3600L) * 1000L
            )
            saveRecord(updated)
            memoryAccessToken = newAccess
            memoryAccessTokenExpiresAtMs = updated.expiresAtMs
            newAccess
        }
    }

    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        val current = loadRecord() ?: return@withContext true
        var remoteConfirmed = false

        try {
            val discovery = getJsonWithRetry(DISCOVERY_ENDPOINT)
            val revocationEndpoint = discovery.optString("revocation_endpoint")
            val refresh = current.refreshToken
            val clientId = current.clientId
            if (revocationEndpoint.isNotBlank() &&
                !refresh.isNullOrBlank() &&
                !clientId.isNullOrBlank()
            ) {
                postFormWithRetry(
                    revocationEndpoint,
                    mapOf(
                        "token" to refresh,
                        "token_type_hint" to "refresh_token",
                        "client_id" to clientId
                    ),
                    allowEmptyBody = true,
                    attempts = 2
                )
                remoteConfirmed = true
            }
        } catch (_: Exception) {
            remoteConfirmed = false
        }

        memoryAccessToken = null
        memoryAccessTokenExpiresAtMs = 0L

        saveRecord(
            current.copy(
                idToken = null,
                accessToken = null,
                refreshToken = null,
                scopes = emptySet(),
                expiresAtMs = 0L
            )
        )
        remoteConfirmed
    }

    fun openUsageSettings() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/#settings/Usage"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun validateIdToken(
        token: String,
        expectedClientId: String,
        expectedNonce: String
    ): JSONObject {
        val parts = token.split(".")
        if (parts.size != 3) throw IllegalStateException("Malformed ID token")

        val header = JSONObject(String(base64UrlDecode(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
        if (header.optString("alg") != "RS256") {
            throw IllegalStateException("Unsupported ID token signing algorithm")
        }

        val kid = header.optString("kid")
        val jwks = getJsonWithRetry(JWKS_ENDPOINT).optJSONArray("keys")
            ?: throw IllegalStateException("OpenAI JWKS response is invalid")

        var keyJson: JSONObject? = null
        for (i in 0 until jwks.length()) {
            val candidate = jwks.optJSONObject(i) ?: continue
            if (candidate.optString("kid") == kid) {
                keyJson = candidate
                break
            }
        }
        val jwk = keyJson ?: throw IllegalStateException("ID token signing key not found")

        val modulus = BigInteger(1, base64UrlDecode(jwk.getString("n")))
        val exponent = BigInteger(1, base64UrlDecode(jwk.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent))

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        if (!verifier.verify(base64UrlDecode(parts[2]))) {
            throw IllegalStateException("ID token signature validation failed")
        }

        if (claims.optString("iss") != ISSUER) {
            throw IllegalStateException("Unexpected ID token issuer")
        }

        val aud = claims.opt("aud")
        val audienceMatches = when (aud) {
            is String -> aud == expectedClientId
            is JSONArray -> (0 until aud.length()).any {
                aud.optString(it) == expectedClientId
            }
            else -> false
        }
        if (!audienceMatches) throw IllegalStateException("ID token audience mismatch")

        if (claims.optLong("exp", 0L) <= System.currentTimeMillis() / 1000L) {
            throw IllegalStateException("ID token is expired")
        }
        if (claims.optString("nonce") != expectedNonce) {
            throw IllegalStateException("ID token nonce mismatch")
        }

        return claims
    }

    private fun getJsonWithRetry(url: String, attempts: Int = 4): JSONObject {
        var last: Exception? = null
        repeat(attempts) { index ->
            try {
                return getJsonOnce(url)
            } catch (e: Exception) {
                if (!isRetryableNetworkError(e)) throw e
                last = e
                if (index < attempts - 1) Thread.sleep(retryDelayMs(index))
            }
        }
        throw networkFailure(last)
    }

    private fun getJsonOnce(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "PRIME-P6/6.0.7")
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("OpenAI request failed with HTTP " + status)
            }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun postFormWithRetry(
        url: String,
        values: Map<String, String>,
        allowEmptyBody: Boolean = false,
        attempts: Int = 4
    ): JSONObject {
        var last: Exception? = null
        repeat(attempts) { index ->
            try {
                return postFormOnce(url, values, allowEmptyBody)
            } catch (e: Exception) {
                if (!isRetryableNetworkError(e)) throw e
                last = e
                if (index < attempts - 1) Thread.sleep(retryDelayMs(index))
            }
        }
        throw networkFailure(last)
    }

    private fun postFormOnce(
        url: String,
        values: Map<String, String>,
        allowEmptyBody: Boolean
    ): JSONObject {
        val payload = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" +
                URLEncoder.encode(it.value, "UTF-8")
        }

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "PRIME-P6/6.0.7")
        conn.outputStream.use {
            it.write(payload.toByteArray(Charsets.UTF_8))
        }

        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val diagnostic = try {
                    val j = JSONObject(body)
                    j.optString("error_description").ifBlank {
                        j.optString("error").ifBlank { "OAuth request failed" }
                    }
                } catch (_: Exception) {
                    "OAuth request failed"
                }
                throw IllegalStateException(diagnostic + " (HTTP " + status + ")")
            }

            if (body.isBlank() && allowEmptyBody) JSONObject() else JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun isRetryableNetworkError(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException
            ) return true
            current = current.cause
        }
        return false
    }

    private fun networkFailure(last: Exception?): IllegalStateException {
        var current: Throwable? = last
        var dns = false
        while (current != null) {
            if (current is UnknownHostException) {
                dns = true
                break
            }
            current = current.cause
        }

        return if (dns) {
            IllegalStateException(
                "PRIME_NETWORK_DNS: PRIME itself cannot resolve auth.openai.com. " +
                    "If a VPN/proxy is enabled, make sure PRIME is included in that VPN, " +
                    "then retry the ChatGPT connection.",
                last
            )
        } else {
            IllegalStateException(
                "PRIME_NETWORK_OPENAI: PRIME could not reach OpenAI after several retries. " +
                    "Check the phone network/VPN and try again.",
                last
            )
        }
    }

    private fun retryDelayMs(index: Int): Long = when (index) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> 6_000L
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { part ->
            val index = part.indexOf("=")
            val key = if (index >= 0) part.substring(0, index) else part
            val value = if (index >= 0) part.substring(index + 1) else ""
            if (key.isBlank()) null else
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }.toMap()
    }

    private fun parseScopes(value: String): Set<String> =
        value.split(" ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return base64Url(data)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(
            value,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
}
