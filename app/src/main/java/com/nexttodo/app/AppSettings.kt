package com.nexttodo.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class AppTheme(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

internal class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "deepseek-key"))

    var theme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.name == preferences.getString("theme", null) }
            ?: AppTheme.SYSTEM
        set(value) = preferences.edit().putString("theme", value.name).apply()

    @Synchronized
    fun readDeepSeekKey(): String? {
        val data = try {
            keyFile.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        val ivLength = data.first().toInt() and 0xff
        require(ivLength in 12..16 && data.size > 1 + ivLength)
        val key = checkNotNull(encryptionKey(keyStore()))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, ivLength))
        return cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength).decodeToString()
    }

    @Synchronized
    fun saveDeepSeekKey(value: String) {
        require(value.isNotBlank())
        val key = encryptionKey(keyStore()) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, KEYSTORE
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv +
            cipher.doFinal(value.encodeToByteArray())
        val output = keyFile.startWrite()
        try {
            output.write(data)
            keyFile.finishWrite(output)
        } catch (exception: Exception) {
            keyFile.failWrite(output)
            throw exception
        }
    }

    @Synchronized
    fun clearDeepSeekKey() {
        keyFile.delete()
        check(!keyFile.baseFile.exists())
    }

    private fun encryptionKey(store: KeyStore): SecretKey? = store.getKey(ALIAS, null) as? SecretKey

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nexttodo_deepseek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
