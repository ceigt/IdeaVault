package com.example.ideavault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedNoteStore(private val context: Context) {
    private val dataFile = File(context.filesDir, "notes.vault")
    private val tempFile = File(context.filesDir, "notes.vault.tmp")

    @Synchronized
    fun load(): List<Note> {
        if (!dataFile.exists()) return emptyList()
        return runCatching {
            val packed = dataFile.readBytes()
            require(packed.size > IV_SIZE)
            val iv = packed.copyOfRange(0, IV_SIZE)
            val encrypted = packed.copyOfRange(IV_SIZE, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            decode(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun save(notes: List<Note>) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(encode(notes).encodeToByteArray())
        tempFile.outputStream().use { output ->
            output.write(cipher.iv)
            output.write(encrypted)
            output.fd.sync()
        }
        if (!tempFile.renameTo(dataFile)) {
            tempFile.copyTo(dataFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(notes: List<Note>): String = JSONArray().apply {
        notes.forEach { note ->
            put(JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.content)
                put("sensitive", note.sensitive)
                put("pinned", note.pinned)
                put("createdAt", note.createdAt)
                put("updatedAt", note.updatedAt)
            })
        }
    }.toString()

    private fun decode(json: String): List<Note> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    Note(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        content = item.getString("content"),
                        sensitive = item.optBoolean("sensitive"),
                        pinned = item.optBoolean("pinned"),
                        createdAt = item.getLong("createdAt"),
                        updatedAt = item.getLong("updatedAt"),
                    ),
                )
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "idea_vault_notes_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
