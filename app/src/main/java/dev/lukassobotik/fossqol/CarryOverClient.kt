package dev.lukassobotik.fossqol

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import okhttp3.*
import okio.ByteString
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
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
class CarryOverClient(private val wsUrl: String = "ws://10.0.2.2:6778/ws") {
    // DER header used in Node.js ("302a300506032b656e032100" hex)
    private val X25519_SPKE_DER_HEADER = hexStringToByteArray("302a300506032b656e032100")

    private val base64 = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()

    private var webSocket: WebSocket? = null
    private var sessionKey: ByteArray? = null
    private val secureRandom = SecureRandom()
    private var messageSeq = 1

    private val pendingMessages = mutableListOf<ByteArray>()

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

    fun sendMessage(message: String) {
        sendMessage(message.toByteArray(Charsets.UTF_8))
    }

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
            }

            ws.send(envelope.toString())
            Log.d("CarryOverClient", "[client] Sent encrypted message: ${String(message)}")
        } else {
            pendingMessages.add(message)
            Log.d("CarryOverClient", "[client] Queued message: ${String(message)}")
        }
    }

    fun start() {
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@CarryOverClient.webSocket = webSocket
            Log.d("CarryOverClient", "[client] Connected to server")
            val hello = JSONObject().apply {
                put("type", "HELLO")
                put("client_pk", base64.encodeToString(clientPubRaw))
                put("nonce", base64.encodeToString(nonceC))
            }
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(webSocket, JSONObject(text))
            } catch (e: Exception) {
                Log.d("CarryOverClient", "[client] Non-JSON or handling error: ${e.localizedMessage}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // support binary
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d("CarryOverClient", "[client] WebSocket failure: ${t.localizedMessage}")
        }
    }

    private fun handleMessage(ws: WebSocket, msg: JSONObject) {
        Log.d("CarryOverClient", "[client] Received: $msg")

        var msgType = msg.optString("type", "")
        var msgCt = msg.optString("ct", null)
        var msgIv = msg.optString("iv", null)

        if (msg.optBoolean("enc", false)) {
            try {
                val iv = base64Decoder.decode(msgIv)
                val ciphertextAndTag = base64Decoder.decode(msgCt)
                val plaintext = decryptChaCha20Poly1305(sessionKey
                    ?: throw IllegalStateException("sessionKey not set"), iv, ciphertextAndTag)
                val json = JSONObject(String(plaintext))
                msgType = json.getString("type")
                msgCt = json.optString("ct", null)
                msgIv = json.optString("iv", null)
                Log.d("CarryOverClient", "[client] Decrypted message: ${String(plaintext)}")
            } catch (e: Exception) {
                Log.d("CarryOverClient", "[client] Failed to decrypt message: ${e.localizedMessage}")
                return
            }
        }

        when (msgType) {
            "WELCOME" -> {
                // ECDH shared secret
                val serverPubRaw = base64Decoder.decode(msg.getString("server_pk"))

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
                val nonceS = base64Decoder.decode(msg.getString("nonce"))
                val salt = ByteArray(32) // 32 zero bytes
                val info = ByteBuffer.allocate("carryover-1".toByteArray().size + nonceC.size + nonceS.size)
                    .put("carryover-1".toByteArray(Charsets.UTF_8))
                    .put(nonceC)
                    .put(nonceS)
                    .array()

                sessionKey = hkdfSha256(sharedSecret, salt, info, 32)
                Log.d("CarryOverClient", "[client] Session key derived: ${sessionKey!!.toHexString()}")

                // Send any queued messages now
                if (pendingMessages.isNotEmpty()) {
                    Log.d("CarryOverClient", "[client] Sending ${pendingMessages.size} queued messages")
                    val queued = pendingMessages.toList()
                    pendingMessages.clear()
                    queued.forEach { sendMessage(it) }
                }

                sendMessage("Hello from kotlin client!")
            }

            "ACK" -> {
                Log.d("CarryOverClient", "[client] ACK received, closing connection...")
                ws.close(1000, "Exchange completed.")
            }

            else -> {
                Log.w("CarryOverClient", "[client] Unhandled message type: $msgType")
            }
        }
    }

    // --- Crypto helpers ---

    private fun generateX25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        // no keysize to set for X25519
        return kpg.generateKeyPair()
    }

    // HKDF-SHA256 (RFC 5869)
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
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

        // convenience: get last N bytes of ByteArray
        private fun ByteArray.takeLast(n: Int): ByteArray {
            return this.copyOfRange(this.size - n, this.size)
        }

        private fun ByteArray.toHexString(): String {
            val sb = StringBuilder()
            for (b in this) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
fun startClientExample() {
    val client = CarryOverClient()
    thread {
        client.start()
        client.sendMessage("Hello from kotlin client!")
    }
}