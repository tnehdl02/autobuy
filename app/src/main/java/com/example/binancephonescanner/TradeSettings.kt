package com.example.binancephonescanner

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** "20:00" style text for a minute-of-day value. */
fun minText(total: Int): String = String.format(Locale.US, "%02d:%02d", total / 60, total % 60)

/**
 * Everything the user can change for auto-buy. Percent values are plain percentages
 * (9.5 means 9.5 %).
 */
data class TradeConfig(
    val autoBuy: Boolean,
    val baseUrl: String,
    val startMin: Int,
    val endMin: Int,
    val endLiquidate: Boolean,
    val slotPct: Double,
    val leverage: Int,
    val maxSlots: Int,
    val targetPct: Double,
    val trailStartPct: Double,
    val trailDropPct: Double
) {
    fun windowText(): String = minText(startMin) + " ~ " + minText(endMin)
}

/** Encrypts small secrets with a key that never leaves the Android Keystore. */
object SecretBox {
    private const val ALIAS = "autobuy_secret_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER)
        store.load(null)
        val existing = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv: ByteArray = cipher.iv
        val data: ByteArray = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    /** Returns null if the text cannot be decrypted (for example after a reinstall). */
    fun decrypt(encoded: String): String? {
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, IV_LENGTH)
            val data = all.copyOfRange(IV_LENGTH, all.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

/** Auto-buy settings and the encrypted API keys, saved on the phone. */
object TradeSettings {
    const val DEFAULT_BASE_URL = "https://fapi.binance.com"
    const val DEFAULT_SLOT_PCT = 9.5
    const val DEFAULT_TARGET_PCT = 5.0
    const val DEFAULT_TRAIL_START_PCT = 3.0
    const val DEFAULT_TRAIL_DROP_PCT = 1.0

    private const val FILE = "autobuy_settings"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): TradeConfig {
        val p = prefs(context)
        return TradeConfig(
            autoBuy = p.getBoolean("auto_buy", false),
            baseUrl = p.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            startMin = p.getInt("start_min", 20 * 60),
            endMin = p.getInt("end_min", 8 * 60),
            endLiquidate = p.getBoolean("end_liquidate", true),
            slotPct = p.getString("slot_pct", null)?.toDoubleOrNull() ?: DEFAULT_SLOT_PCT,
            leverage = p.getInt("leverage", 1),
            maxSlots = p.getInt("max_slots", 10),
            targetPct = p.getString("target_pct", null)?.toDoubleOrNull() ?: DEFAULT_TARGET_PCT,
            trailStartPct = p.getString("trail_start_pct", null)?.toDoubleOrNull() ?: DEFAULT_TRAIL_START_PCT,
            trailDropPct = p.getString("trail_drop_pct", null)?.toDoubleOrNull() ?: DEFAULT_TRAIL_DROP_PCT
        )
    }

    /** Saves every setting except the auto-buy switch. */
    fun save(context: Context, config: TradeConfig) {
        prefs(context).edit()
            .putString("base_url", config.baseUrl)
            .putInt("start_min", config.startMin)
            .putInt("end_min", config.endMin)
            .putBoolean("end_liquidate", config.endLiquidate)
            .putString("slot_pct", config.slotPct.toString())
            .putInt("leverage", config.leverage)
            .putInt("max_slots", config.maxSlots)
            .putString("target_pct", config.targetPct.toString())
            .putString("trail_start_pct", config.trailStartPct.toString())
            .putString("trail_drop_pct", config.trailDropPct.toString())
            .apply()
    }

    fun setAutoBuy(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_buy", enabled).apply()
    }

    fun setKeys(context: Context, apiKey: String, apiSecret: String) {
        prefs(context).edit()
            .putString("api_key_enc", SecretBox.encrypt(apiKey.trim()))
            .putString("api_secret_enc", SecretBox.encrypt(apiSecret.trim()))
            .apply()
    }

    fun getKey(context: Context): String? =
        prefs(context).getString("api_key_enc", null)?.let { SecretBox.decrypt(it) }

    fun getSecret(context: Context): String? =
        prefs(context).getString("api_secret_enc", null)?.let { SecretBox.decrypt(it) }

    fun hasKeys(context: Context): Boolean =
        !getKey(context).isNullOrBlank() && !getSecret(context).isNullOrBlank()

    /** "••••abcd" for the saved API key, or null if none is saved. */
    fun maskedKey(context: Context): String? {
        val key = getKey(context) ?: return null
        if (key.isBlank()) return null
        return "••••" + key.takeLast(4)
    }
}
