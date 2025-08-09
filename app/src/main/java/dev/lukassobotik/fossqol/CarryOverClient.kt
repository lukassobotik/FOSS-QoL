package dev.lukassobotik.fossqol

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import okhttp3.*
import okio.ByteString
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// TODO: Handle unsupported versions
@RequiresApi(Build.VERSION_CODES.O)
class CarryOverClient(private val context: Context, private val wsUrl: String = "ws://10.0.2.2:6778/ws") {
    // DER header used in Node.js ("302a300506032b656e032100" hex)
    private val X25519_SPKE_DER_HEADER = hexStringToByteArray("302a300506032b656e032100")

    private val base64 = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    private var webSocket: WebSocket? = null
    private var sessionKey: ByteArray? = null
    private val secureRandom = SecureRandom()
    private var messageSeq = 1

    // Queues for messages sent before the session key is ready
    private val pendingRawMessages = mutableListOf<ByteArray>()
    private val pendingJsonMessages = mutableListOf<JSONObject>()

    // ephemeral keys
    private val clientKeyPair: KeyPair by lazy { generateX25519KeyPair() }
    private val clientPubRaw: ByteArray by lazy {
        val encoded = clientKeyPair.public.encoded
        encoded.copyOfRange(encoded.size - 32, encoded.size)
    }
    private val nonceC: ByteArray by lazy {
        ByteArray(12).also { secureRandom.nextBytes(it) }
    }

    private val client = OkHttpClient.Builder().build()

    // persistent per-install device ID (8 random bytes hex)
    private val deviceId: String by lazy { getOrCreateDeviceId() }

    fun sendScrollInfo(message: String) {
        val obj = JSONObject().apply {
            put("msg_id", message + messageSeq)
            put("to", JSONArray().put("2ba77a64b0e592f1"))
            put("type", "SEND")
            put("payload_meta", "url")
            put("payload_plain", message)
            put("ts", System.currentTimeMillis())
            put("enc", true)
        }

        sendEncrypted(obj)
    }

    /**
     * Raw byte payload encryption path.
     * Uses sessionKey if present, otherwise queues.
     */
    fun sendMessage(message: ByteArray) {
        val key = sessionKey
        val ws = webSocket

        if (key != null && ws != null) {
            val msgNonce = ByteArray(12).also { secureRandom.nextBytes(it) }
            val ctAndTag = encryptChaCha20Poly1305(key, msgNonce, message)

            val envelope = JSONObject().apply {
                put("seq", messageSeq++)
                put("iv", base64.encodeToString(msgNonce))
                put("ct", base64.encodeToString(ctAndTag))
                put("enc", true)
                put("from_device", deviceId)
            }

            ws.send(envelope.toString())
            Log.d("CarryOverClient", "[client:$deviceId] Sent encrypted message: ${String(message)}")
        } else {
            pendingRawMessages.add(message)
            Log.d("CarryOverClient", "[client:$deviceId] Queued message: ${String(message)}")
        }
    }

    /**
     * Send a structured JSON object encrypted (mirrors JS sendEncrypted).
     * Adds seq and from_device automatically. Queues until session established.
     */
    fun sendEncrypted(obj: JSONObject) {
        val key = sessionKey
        val ws = webSocket

        // attach seq and from_device before encrypting
        obj.put("seq", messageSeq++)
        obj.put("from_device", deviceId)

        val plaintext = obj.toString().toByteArray(Charsets.UTF_8)
        if (key != null && ws != null) {
            val msgNonce = ByteArray(12).also { secureRandom.nextBytes(it) }
            val ctAndTag = encryptChaCha20Poly1305(key, msgNonce, plaintext)

            val envelope = JSONObject().apply {
                put("iv", base64.encodeToString(msgNonce))
                put("ct", base64.encodeToString(ctAndTag))
                put("enc", true)
                put("seq", obj.getInt("seq"))
                put("from_device", obj.getString("from_device"))
            }
            ws.send(envelope.toString())
            Log.d("CarryOverClient", "[client:$deviceId] Sent encrypted JSON: ${obj}")
        } else {
            pendingJsonMessages.add(obj)
            Log.d("CarryOverClient", "[client:$deviceId] Queued JSON message: ${obj}")
        }
    }

    fun start() {
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@CarryOverClient.webSocket = webSocket
            Log.d("CarryOverClient", "[client:$deviceId] Connected to server")
            val hello = JSONObject().apply {
                put("type", "HELLO")
                put("client_pk", base64.encodeToString(clientPubRaw))
                put("nonce", base64.encodeToString(nonceC))
                put("device_id", deviceId)
            }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(webSocket, JSONObject(text))
            } catch (e: Exception) {
                Log.d("CarryOverClient", "[client:$deviceId] Non-JSON or handling error: ${e.localizedMessage}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // support binary
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d("CarryOverClient", "[client:$deviceId] WebSocket failure: ${t.localizedMessage}")
        }
    }

    private fun handleMessage(ws: WebSocket, rawMsg: JSONObject) {
        Log.d("CarryOverClient", "[client:$deviceId] Received (raw): $rawMsg")

        // If message is marked encrypted, decrypt into an effectiveMsg, else use rawMsg.
        val effectiveMsg: JSONObject = if (rawMsg.optBoolean("enc", false)) {
            try {
                val iv = base64Decoder.decode(rawMsg.getString("iv"))
                val ciphertextAndTag = base64Decoder.decode(rawMsg.getString("ct"))
                val plaintext = decryptChaCha20Poly1305(
                    sessionKey ?: throw IllegalStateException("sessionKey not set"),
                    iv,
                    ciphertextAndTag
                )
                val decoded = JSONObject(String(plaintext))
                Log.d("CarryOverClient", "[client:$deviceId] Decrypted message: $decoded")
                decoded
            } catch (e: Exception) {
                Log.d("CarryOverClient", "[client:$deviceId] Failed to decrypt message: ${e.localizedMessage}")
                return
            }
        } else {
            rawMsg
        }

        val msgType = effectiveMsg.optString("type", "")

        when (msgType) {
            "WELCOME" -> {
                // ECDH shared secret
                val serverPubRaw = base64Decoder.decode(effectiveMsg.getString("server_pk"))

                // reconstruct SPKI DER (header + raw)
                val serverSpki = ByteBuffer.allocate(X25519_SPKE_DER_HEADER.size + serverPubRaw.size)
                    .put(X25519_SPKE_DER_HEADER)
                    .put(serverPubRaw)
                    .array()

                val kf = KeyFactory.getInstance("X25519")
                val serverPublicKey = kf.generatePublic(X509EncodedKeySpec(serverSpki))

                val keyAgreement = KeyAgreement.getInstance("X25519")
                keyAgreement.init(clientKeyPair.private)
                keyAgreement.doPhase(serverPublicKey, true)
                val sharedSecret = keyAgreement.generateSecret()

                // HKDF-SHA256
                val nonceS = base64Decoder.decode(effectiveMsg.getString("nonce"))
                val salt = ByteArray(32) // 32 zero bytes
                val info = ByteBuffer.allocate("carryover-1".toByteArray().size + nonceC.size + nonceS.size)
                    .put("carryover-1".toByteArray(Charsets.UTF_8))
                    .put(nonceC)
                    .put(nonceS)
                    .array()

                sessionKey = hkdfSha256(sharedSecret, salt, info, 32)
                Log.d("CarryOverClient", "[client:$deviceId] Session key derived: ${sessionKey!!.toHexString()}")

//                // Send REGISTER as first encrypted message (mirrors JS)
//                val reg = JSONObject().apply {
//                    put("type", "REGISTER")
//                    put("device_id", deviceId)
//                    put("ts", System.currentTimeMillis())
//                    // seq + from_device will be added by sendEncrypted()
//                }
//                sendEncrypted(reg)

                // Flush pending messages (JSON and raw)
                if (pendingJsonMessages.isNotEmpty()) {
                    Log.d("CarryOverClient", "[client:$deviceId] Sending ${pendingJsonMessages.size} queued JSON messages")
                    val queued = pendingJsonMessages.toList()
                    pendingJsonMessages.clear()
                    queued.forEach { sendEncrypted(it) }
                }
                if (pendingRawMessages.isNotEmpty()) {
                    Log.d("CarryOverClient", "[client:$deviceId] Sending ${pendingRawMessages.size} queued raw messages")
                    val queuedRaw = pendingRawMessages.toList()
                    pendingRawMessages.clear()
                    queuedRaw.forEach { sendMessage(it) }
                }
            }

            "REGISTERED" -> {
                Log.d("CarryOverClient", "[client:$deviceId] Successfully registered.")
            }

            "FORWARD" -> {
                // Received application message from another device via server
                val fromDevice = effectiveMsg.optString("from_device", "unknown")
                val seq = effectiveMsg.optInt("seq", -1)
                val payload = effectiveMsg.opt("payload") // could be object or string

                Log.d("CarryOverClient", "[client:$deviceId] Received MSG from $fromDevice seq=$seq payload=$payload")

                // Send ACK (unencrypted) so server can route / mark delivered
                val ackObj = JSONObject().apply {
                    put("type", "ACK")
                    put("ack_for", seq)
                    put("to", fromDevice)
                    put("from_device", deviceId)
                }
                ws.send(ackObj.toString())
                Log.d("CarryOverClient", "[client:$deviceId] Sent ACK for seq=$seq to $fromDevice")
            }

            "ACK" -> {
                val ackFor = effectiveMsg.optInt("ack_for", -1)
                val fromDevice = effectiveMsg.optString("from_device", "unknown")
                Log.d("CarryOverClient", "[client:$deviceId] Received ACK for seq $ackFor from $fromDevice")
            }

            else -> {
                Log.w("CarryOverClient", "[client:$deviceId] Unhandled message type: $msgType")
            }
        }
    }

    // --- Crypto helpers ---

    private fun generateX25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        return kpg.generateKeyPair()
    }

    // HKDF-SHA256 (RFC 5869)
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
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

    private fun encryptChaCha20Poly1305(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val engine = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, nonce, null)
        engine.init(true, params)
        val out = ByteArray(engine.getOutputSize(plaintext.size))
        var len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += engine.doFinal(out, len)
        return out.copyOf(len) // ciphertext || tag
    }

    private fun decryptChaCha20Poly1305(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val engine = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), 128, nonce, null)
        engine.init(false, params)
        val out = ByteArray(engine.getOutputSize(ciphertextAndTag.size))
        var len = engine.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
        len += engine.doFinal(out, len)
        return out.copyOf(len)
    }

    // --- Utilities ---

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("carryover_prefs", Context.MODE_PRIVATE)
        val key = "device_id"
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrEmpty()) return existing

        val bytes = ByteArray(8).also { secureRandom.nextBytes(it) }
        val hex = bytes.toHexString()
        prefs.edit().putString(key, hex).apply()
        return hex
    }

    companion object {
        // helper: hex -> byte[]
        private fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private fun ByteArray.toHexString(): String {
            val sb = StringBuilder()
            for (b in this) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun startClientExample(context: Context) {
    val client = CarryOverClient(context)
    thread {
        client.start()
        client.sendScrollInfo("eyJ1cmwiOiJiYmMuY29tXC9waWRnaW5cL2FydGljbGVzXC9jNzllMnAwZHhnOW8iLCJzbmlwcGV0cyI6WyJXYXRlcm1lbG9uOiBIZWFsdGggYmVuZWZpdHMgb2Ygd2F0ZXJtZWxvbiAtIEJCQyBOZXdzIFBpZGdpbiIsIlNoZSBhZGQgc2F5IGFsbCBkaXMgYmV0YSB0aW5zIHdleSB3YXRlcm1lbG9uIGRleSBkbyBuYSBzYWtlIG9mIGRpIHdhdGVyIHdleSBmdWxsIGFtIGFuZCBzb21lIHNwZWNpYWwgdGlucyB3ZXkgZGV5IGluc2lkZSBsaWtlIGx5Y29wZW5lICh3ZXkgZGV5IG1ha2UgYW0gcmVkKSBhbmQgY2l0cnVsbGluZS4iLCJXYXRlcm1lbG9uIG5hIG5hdHVyYWwgc291cmNlIG9mIGNpdHJ1bGxpbmUuIENpdHJ1bGxpbmUgbmEgYW1pbm8gYWNpZCB3ZXkgZml0IHN1cHBvcnQgYmV0YSBlcmVjdGlvbnMuIiwiRGlldGl0aWFuIFN1bGFpbWFuIHNheSB3YXRlcm1lbG9uIG5hIG5hdHVyYWwgVmlhZ3JhIHdleSBkZXkgaGVscCBtZW4gd2V5IGdldCBsb3cgc2V4dWFsIHBlcmZvcm1hbmNlLCBhcyBlIGRleSBpbmNyZWFzZSBibG9vZCBmbG93IHRvIGRpIHBlbmlzLCB3ZXkgZGV5IGFsbG93IG1lbiB0byBlYXNpbHkgZ2V0IGVyZWN0aW9uIHdpdGhvdXQgYXJvdXNhbC4iLCJcIlJlc2VhcmNoIGRvbiBzaG93IHNheSBjaXRydWxsaW5lIHdleSBkZXkgd2F0ZXJtZWxvbiBmaXQgaGVscCBtZW4gd2V5IGdldCBzbWFsbCB3YWhhbGEgd2l0IHBlcmZvcm1hbmNlIGFzIGUgZGV5IGhlbHAgYmxvb2QgZmxvdyB3ZWxsIHRvIGRpIHBlbmlzXCIgc2hlIGFkZC4iLCJBbm90aGVyIHRoaW5nIHdlIGRlIGZvciBpbnNpZGUgZGlzIHRvcmkiLCJDbGljayBoZXJlIHRvIGpvaW4gQkJDIFBpZGdpbiBXaGF0c2FwcCBDaGFubmVsIiwiV2h5IGRpIGRlbWFuZCBmb3IgbWF0Y2hhIHRlYSBkZXkgZHJ5IHVwIGdsb2JhbCBzdXBwbHkgMjZ0aCBKdWx5IDIwMjUiLCJXaHkgZGkgZGVtYW5kIGZvciBtYXRjaGEgdGVhIGRleSBkcnkgdXAgZ2xvYmFsIHN1cHBseSIsIjI2dGggSnVseSAyMDI1Il19")
    }
}