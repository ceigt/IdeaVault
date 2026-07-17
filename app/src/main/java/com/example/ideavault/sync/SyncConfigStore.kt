package com.example.ideavault.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SyncConfig(
    val serverUrl: String,
    val accessToken: String,
    val encryptionPassphrase: String,
)

class SyncConfigStore(context: Context) {
    private val file = File(context.filesDir, "sync_config.vault")

    @Synchronized
    fun load(): SyncConfig? {
        if (!file.exists()) return null
        return runCatching {
            val packed = file.readBytes()
            require(packed.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_SIZE)),
                )
            }
            val json = JSONObject(cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).decodeToString())
            SyncConfig(
                serverUrl = json.getString("serverUrl"),
                accessToken = json.getString("accessToken"),
                encryptionPassphrase = json.getString("encryptionPassphrase"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(config: SyncConfig) {
        val json = JSONObject().apply {
            put("serverUrl", config.serverUrl)
            put("accessToken", config.accessToken)
            put("encryptionPassphrase", config.encryptionPassphrase)
        }.toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        file.writeBytes(cipher.iv + cipher.doFinal(json.encodeToByteArray()))
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
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
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "idea_vault_sync_config_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}