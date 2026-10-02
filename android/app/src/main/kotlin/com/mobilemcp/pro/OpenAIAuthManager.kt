package com.mobilemcp.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
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
 * PRIME never stores an OpenAI API key. The user's authorized ChatGPT plan
 * supplies a short-lived OAuth access token for eligible Responses requests.
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
        private const val HOST_PREFS = "prime_p6_host"
        private const val HOST_ID_KEY = "ext_agent_host_id"
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
    private val refreshMutex = Mutex()
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
                subject = json.optString("subject").ifBlank { null },
                email = json.optString("email").ifBlank { null },
                name = json.optString("name").ifBlank { null },
                clientId = json.optString("client_id").ifBlank { null },
                idToken = json.optString("id_token").ifBlank { null },
                accessToken = json.optString("access_token").ifBlank { null },
                refreshToken = json.optString("refresh_token").ifBlank { null },
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

    /**
     * Starts a loopback callback before opening the system browser.
     * The caller only needs to launch the supplied URI.
     */
    suspend fun signIn(openBrowser: (Uri) -> Unit): ChatGptProfile = withContext(Dispatchers.IO) {
        val previous = loadRecord()
        val returningClientId = previous?.clientId?.takeIf { it.startsWith("oaiapp_") }
        val requestClientId = returningClientId ?: DYNAMIC_CLIENT_ID

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

        if (returningClientId == null) {
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
                val requestLine = reader.readLine() ?: throw IllegalStateException("Empty OAuth callback")
                val target = requestLine.split(" ").getOrNull(1)
                    ?: throw IllegalStateException("Invalid OAuth callback")
                val callbackUri = URI("http://127.0.0.1" + target)
                val params = parseQuery(callbackUri.rawQuery.orEmpty())

                val ok = params["state"] == state && params["error"].isNullOrBlank()
                val body = if (ok) {
                    "<html><body style='font-family:sans-serif;background:#090d18;color:#fff;padding:36px'><h2>PRIME connected</h2><p>You can return to the PRIME app.</p></body></html>"
                } else {
                    "<html><body style='font-family:sans-serif;padding:36px'><h2>PRIME sign-in failed</h2><p>Return to the app and try again.</p></body></html>"
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = it.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                output.write(bytes)
                output.flush()
                params
            }
        } finally {
            server.close()
        }

        if (callback["state"] != state) throw IllegalStateException("OAuth state mismatch")
        callback["error"]?.let { error ->
            throw IllegalStateException("ChatGPT authorization was not completed: " + error)
        }

        val code = callback["code"] ?: throw IllegalStateException("Authorization code missing")
        val callbackClientId = callback["client_id"]

        val issuedClientId = if (returningClientId == null) {
            callbackClientId?.takeIf { it.startsWith("oaiapp_") }
                ?: throw IllegalStateException("OpenAI did not return an issued client ID")
        } else {
            if (!callbackClientId.isNullOrBlank() && callbackClientId != returningClientId) {
                throw IllegalStateException("Returned client ID does not match the saved registration")
            }
            returningClientId
        }

        val tokenJson = postForm(
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

        if (returningClientId != null &&
            !previous?.subject.isNullOrBlank() &&
            previous?.subject != subject
        ) {
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
            email = claims.optString("email").ifBlank { null },
            name = claims.optString("name").ifBlank {
                claims.optString("preferred_username").ifBlank { null }
            },
            clientId = issuedClientId,
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            scopes = grantedScopes,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
        )
        saveRecord(record)

        ChatGptProfile(
            subject = subject,
            email = record.email,
            name = record.name,
            clientId = issuedClientId,
            planSharingEnabled = true
        )
    }

    suspend fun accessToken(): String = refreshMutex.withLock {
        val current = loadRecord() ?: throw IllegalStateException("Continue with ChatGPT first")
        val access = current.accessToken
        if (!access.isNullOrBlank() && current.expiresAtMs - System.currentTimeMillis() > 120_000L) {
            return@withLock access
        }

        val refresh = current.refreshToken
            ?: throw IllegalStateException("ChatGPT session needs to be connected again")
        val clientId = current.clientId
            ?: throw IllegalStateException("Saved ChatGPT registration is incomplete")

        val tokenJson = withContext(Dispatchers.IO) {
            postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refresh,
                    "resource" to RESOURCE
                )
            )
        }

        val newAccess = tokenJson.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI refresh did not return an access token")
        val newRefresh = tokenJson.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI refresh did not return a replacement refresh token")
        val newIdToken = tokenJson.optString("id_token").takeIf { it.isNotBlank() } ?: current.idToken
        val scopes = parseScopes(tokenJson.optString("scope")).ifEmpty { current.scopes }
        if (!scopes.contains(REQUIRED_SCOPE)) {
            throw IllegalStateException("ChatGPT plan usage is no longer enabled for PRIME")
        }

        val updated = current.copy(
            idToken = newIdToken,
            accessToken = newAccess,
            refreshToken = newRefresh,
            scopes = scopes,
            expiresAtMs = System.currentTimeMillis() + tokenJson.optLong("expires_in", 3600L) * 1000L
        )
        saveRecord(updated)
        return@withLock newAccess
    }

    /**
     * Revokes the renewable session when possible, then clears local tokens.
     * Registration identity/client ID is retained so the same account can reconnect.
     */
    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        val current = loadRecord() ?: return@withContext true
        var remoteConfirmed = false

        try {
            val discovery = getJson(DISCOVERY_ENDPOINT)
            val revocationEndpoint = discovery.optString("revocation_endpoint")
            val refresh = current.refreshToken
            val clientId = current.clientId
            if (revocationEndpoint.isNotBlank() && !refresh.isNullOrBlank() && !clientId.isNullOrBlank()) {
                postForm(
                    revocationEndpoint,
                    mapOf(
                        "token" to refresh,
                        "token_type_hint" to "refresh_token",
                        "client_id" to clientId
                    ),
                    allowEmptyBody = true
                )
                remoteConfirmed = true
            }
        } catch (_: Exception) {
            remoteConfirmed = false
        }

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

    private fun validateIdToken(token: String, expectedClientId: String, expectedNonce: String): JSONObject {
        val parts = token.split(".")
        if (parts.size != 3) throw IllegalStateException("Malformed ID token")

        val header = JSONObject(String(base64UrlDecode(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
        if (header.optString("alg") != "RS256") {
            throw IllegalStateException("Unsupported ID token signing algorithm")
        }

        val kid = header.optString("kid")
        val jwks = getJson(JWKS_ENDPOINT).optJSONArray("keys")
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
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == expectedClientId }
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

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
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

    private fun postForm(
        url: String,
        values: Map<String, String>,
        allowEmptyBody: Boolean = false
    ): JSONObject {
        val payload = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

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
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
