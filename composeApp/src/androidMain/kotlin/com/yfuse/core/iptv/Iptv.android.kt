package com.yfuse.core.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Playlist URLs can contain access tokens, so the entire private snapshot is encrypted. */
actual fun createIptvStorage(): IptvStorage =
    object : IptvStorage {
        private fun target(): File = requireNotNull(androidAppContext).filesDir.resolve("iptv-v1.enc")

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("yfuse-iptv-v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(
                                "yfuse-iptv-v1",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            ).setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM,
                            ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
        }

        override fun read(): String? {
            val file = target()
            if (!file.exists()) return null
            require(file.length() in 28..(MAX_IPTV_STORAGE_BYTES + 28L)) { "订阅存储大小无效" }
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        }

        override fun write(text: String) {
            val plain = text.toByteArray(Charsets.UTF_8)
            require(plain.size <= MAX_IPTV_STORAGE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(plain)
            val target = target()
            val staged = File(target.parentFile, target.name + ".tmp")
            try {
                staged.outputStream().use {
                    it.write(bytes)
                    it.fd.sync()
                }
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                staged.delete()
            }
        }
    }

private val playlistHttp by lazy {
    OkHttpClient
        .Builder()
        .connectTimeout(
            15,
            TimeUnit.SECONDS,
        ).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
}

actual suspend fun downloadM3u(url: String): String {
    require(isIptvHttpUrl(url))
    val context = currentCoroutineContext()
    return runInterruptible(Dispatchers.IO) {
        val call = playlistHttp.newCall(Request.Builder().url(url).build())
        try {
            call.execute().use { response ->
                require(response.isSuccessful) { "订阅请求失败（${response.code}）" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= MAX_M3U_BYTES) { "订阅超过 4 MB" }
                body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_M3U_BYTES) { "订阅超过 4 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            }
        } finally {
            call.cancel()
        }
    }
}
