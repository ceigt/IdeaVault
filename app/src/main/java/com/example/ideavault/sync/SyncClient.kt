package com.example.ideavault.sync

import android.util.Base64
import com.example.ideavault.data.Note
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SyncResult(val notes: List<Note>, val username: String)
data class AuthResult(val accessToken: String, val username: String)

class SyncClient {
    fun authenticate(serverUrl: String, username: String, password: String, register: Boolean): AuthResult {
        val baseUrl = serverUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("https://")) { "服务器地址必须使用 HTTPS" }
        val endpoint = if (register) "register" else "login"
        val payload = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .toString()
        val response = JSONObject(request("POST", "$baseUrl/v1/auth/$endpoint", body = payload))
        return AuthResult(response.getString("accessToken"), response.getString("username"))
    }
    fun sync(config: SyncConfig, localNotes: List<Note>): SyncResult {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("https://")) { "服务器地址必须使用 HTTPS" }

        val serverConfig = request("GET", "$baseUrl/v1/config", config.accessToken)
        val configJson = JSONObject(serverConfig)
        val username = configJson.getString("username")
        val keyBytes = Base64.decode(configJson.getString("dataKey"), Base64.DEFAULT)
        require(keyBytes.size == 32) { "服务器 DATA_KEY 无效" }
        val key = SecretKeySpec(keyBytes, "AES")

        val outgoing = JSONArray().apply {
            localNotes.forEach { note -> put(encryptEnvelope(note, key)) }
        }
        val keyId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded),
            Base64.NO_WRAP,
        )
        val payload = JSONObject().put("keyId", keyId).put("notes", outgoing).toString()
        val response = JSONObject(request("POST", "$baseUrl/v1/sync", config.accessToken, payload))
        val notes = response.getJSONArray("notes")
        val decrypted = buildList {
            for (index in 0 until notes.length()) {
                add(decryptEnvelope(notes.getJSONObject(index), key))
            }
        }
        return SyncResult(decrypted, username)
    }

    private fun encryptEnvelope(note: Note, key: SecretKeySpec): JSONObject {
        val plaintext = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("content", note.content)
            put("sensitive", note.sensitive)
            put("pinned", note.pinned)
            put("createdAt", note.createdAt)
            put("updatedAt", note.updatedAt)
            put("deleted", note.deleted)
        }.toString().encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return JSONObject().apply {
            put("id", note.id)
            put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("updatedAt", note.updatedAt)
        }
    }

    private fun decryptEnvelope(envelope: JSONObject, key: SecretKeySpec): Note {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_BITS, Base64.decode(envelope.getString("iv"), Base64.DEFAULT)),
                )
            }
            val json = JSONObject(
                cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.DEFAULT)).decodeToString(),
            )
            require(json.getString("id") == envelope.getString("id"))
            return Note(
                id = json.getString("id"),
                title = json.getString("title"),
                content = json.getString("content"),
                sensitive = json.optBoolean("sensitive"),
                pinned = json.optBoolean("pinned"),
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
                deleted = json.optBoolean("deleted"),
            )
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("服务器 DATA_KEY 不匹配，或密文已损坏", error)
        }
    }

    private fun request(method: String, url: String, token: String? = null, body: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(response).optString("error") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "服务器返回错误 $status")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}