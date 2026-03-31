package dev.lukassobotik.fossqol.utils

import android.content.Context
import android.util.Base64
import dev.lukassobotik.fossqol.SHARED_PREFERENCES_PAIRED_DEVICES
import dev.lukassobotik.fossqol.SHARED_PREFERENCES_PAIRED_DEVICE_IDS
import okhttp3.internal.http2.Header
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// --- SharedPreferences ---

fun savePairedDeviceIDs(context: Context, fields: List<String>) {
    val jsonArray = JSONArray()
    fields.filter { it.isNotBlank() }.forEach { jsonArray.put(it) }

    context.getSharedPreferences(SHARED_PREFERENCES_PAIRED_DEVICES, Context.MODE_PRIVATE)
        .edit()
        .putString(SHARED_PREFERENCES_PAIRED_DEVICE_IDS, jsonArray.toString())
        .apply()
}

fun loadPairedDeviceIDs(context: Context): MutableList<String> {
    val prefs = context.getSharedPreferences(SHARED_PREFERENCES_PAIRED_DEVICES, Context.MODE_PRIVATE)
    val jsonString = prefs.getString(SHARED_PREFERENCES_PAIRED_DEVICE_IDS, null) ?: return mutableListOf("")
    return try {
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        // Always ensure an empty field at the end
        if (list.isEmpty() || list.last().isNotBlank()) list.add("")
        list
    } catch (e: Exception) {
        mutableListOf("")
    }
}

internal fun saveToSharedPreferences(context: Context, prefId: String, key: String, string: String) {
    context.getSharedPreferences(prefId, Context.MODE_PRIVATE)
        .edit()
        .putString(key, string)
        .apply()
}

internal fun loadFromSharedPreferences(context: Context, prefId: String, key: String): String {
    val prefs = context.getSharedPreferences(prefId, Context.MODE_PRIVATE)
    return prefs.getString(key, null) ?: ""
}

// --- Crypto helpers ---

internal fun generateX25519KeyPair(): KeyPair {
    val kpg = KeyPairGenerator.getInstance("X25519")
    return kpg.generateKeyPair()
}

// HKDF-SHA256 (RFC 5869)
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    // Extract (PRK)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)

    // Expand
    var t = ByteArray(0)
    val okm = ByteArray(length)
    var produced = 0
    var counter = 1
    while (produced < length) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(t)
        mac.update(info)
        mac.update(counter.toByte())
        t = mac.doFinal()
        val toCopy = Math.min(t.size, length - produced)
        System.arraycopy(t, 0, okm, produced, toCopy)
        produced += toCopy
        counter++
    }
    return okm
}

internal fun encryptChaCha20Poly1305(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
    val engine = ChaCha20Poly1305()
    val params = AEADParameters(KeyParameter(key), 128, nonce, null)
    engine.init(true, params)
    val out = ByteArray(engine.getOutputSize(plaintext.size))
    var len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
    len += engine.doFinal(out, len)
    return out.copyOf(len) // ciphertext || tag
}

internal fun decryptChaCha20Poly1305(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
    val engine = ChaCha20Poly1305()
    val params = AEADParameters(KeyParameter(key), 128, nonce, null)
    engine.init(false, params)
    val out = ByteArray(engine.getOutputSize(ciphertextAndTag.size))
    var len = engine.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
    len += engine.doFinal(out, len)
    return out.copyOf(len)
}

internal fun aesGcmEncrypt(key: ByteArray, iv12: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv12)
    val secret = SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secret, spec)
    if (aad != null) cipher.updateAAD(aad)
    return cipher.doFinal(plaintext) // ciphertext || tag
}

internal fun aesGcmDecrypt(key: ByteArray, iv12: ByteArray, ctAndTag: ByteArray, aad: ByteArray? = null): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv12)
    val secret = SecretKeySpec(key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secret, spec)
    if (aad != null) cipher.updateAAD(aad)
    return cipher.doFinal(ctAndTag)
}

internal fun publicKeyRaw32(pubKey: java.security.PublicKey): ByteArray {
    val enc = pubKey.encoded
    // assume last 32 bytes are raw X25519 public (same as Node trick)
    return enc.copyOfRange(enc.size - 32, enc.size)
}

internal fun spkiFromRaw(raw32: ByteArray, header: ByteArray): ByteArray {
    // use the same DER header as Node/earlier code
    val out = ByteArray(header.size + raw32.size)
    System.arraycopy(header, 0, out, 0, header.size)
    System.arraycopy(raw32, 0, out, header.size, raw32.size)
    return out
}

// --- Other Utils ---

fun formatDataToBase64(url: String, snippets: List<String>): String {
    val json = JSONObject().apply {
        put("url", url)
        put("snippets", JSONArray(snippets))
    }

    val jsonString = json.toString()
    return Base64.encodeToString(jsonString.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}