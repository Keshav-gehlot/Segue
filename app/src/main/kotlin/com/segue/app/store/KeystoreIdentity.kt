package com.segue.app.store

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import com.segue.core.Identity
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec

/**
 * Long-term identity key in AndroidKeyStore (StrongBox when available). The private key never
 * leaves secure hardware; core's Identity signs through the normal JCA API, which Android routes
 * to the keystore provider.
 */
object KeystoreIdentity {
    private const val ALIAS = "segue-identity-v1"
    private const val TAG = "SegueIdentity"

    fun loadOrCreate(): Identity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return Identity(KeyPair(it.certificate.publicKey, it.privateKey))
        }
        return try {
            Identity(generate(strongBox = true))
        } catch (e: StrongBoxUnavailableException) {
            Identity(generate(strongBox = false))
        } catch (e: ProviderException) {
            Log.w(TAG, "StrongBox key generation failed, using TEE", e)
            Identity(generate(strongBox = false))
        }
    }

    private fun generate(strongBox: Boolean): KeyPair {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }
}

/** Changes on every boot; lets the engine detect that elapsedRealtime restarted. */
object BootId {
    fun read(context: Context): String =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
}
