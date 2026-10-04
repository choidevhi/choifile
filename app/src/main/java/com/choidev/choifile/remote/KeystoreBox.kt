package com.choidev.choifile.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256 key that lives inside the Android Keystore and cannot be exported. It protects the connection file
 * against someone copying the app's data off the phone; it does not protect against code running as this app.
 * No user authentication is required for the key so background transfers keep working.
 */
object KeystoreKey {
    private const val ALIAS = "choifile_connections_v1"

    @Synchronized fun get(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun repo(context: Context) = ConnectionRepo(File(context.filesDir, "connections.enc"), AesGcmBox { get() })
}
