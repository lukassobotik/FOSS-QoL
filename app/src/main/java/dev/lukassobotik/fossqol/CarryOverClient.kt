package dev.lukassobotik.fossqol

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.lukassobotik.fossqol.utils.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.KeyAgreement

// TODO: Handle unsupported versions
@RequiresApi(Build.VERSION_CODES.O)
class CarryOverClient(private val context: Context) {
    private var wsUrl: String = "" //ws://10.0.2.2:6778/ws
    private var pairedDevices: JSONArray = JSONArray()

    private val verboseLogging: Boolean = false

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
            put("msg_id", createMessageId())
            put("to", pairedDevices)
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
     * Send a structured JSON object encrypted.
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
        loadUserConfig()
        val request = Request.Builder().url(wsUrl).build()
        client.newWebSocket(request, socketListener)
    }

    fun loadUserConfig() {
        val savedDevices = loadFromSharedPreferences(context, SHARED_PREFERENCES_PAIRED_DEVICES, SHARED_PREFERENCES_PAIRED_DEVICE_IDS)
        try {
            pairedDevices = JSONArray(savedDevices)
        } catch (e: Exception) {
            if (savedDevices.isEmpty()) {
                Log.d("CarryOverClient", "[client:$deviceId] User has no paired devices.")
            } else {
                Log.w("CarryOverClient", "Error loading preferences", e)
            }
        }
        var serverAddress = loadFromSharedPreferences(context, SHARED_PREFERENCES_SERVER_IP, SHARED_PREFERENCES_SERVER_IP)
        if (serverAddress.contains(":")) serverAddress = serverAddress.substringBefore(":")
        serverAddress = "ws://$serverAddress:6778/ws"
        Log.d("CarryOverClient", "[client:$deviceId] Server address: $serverAddress")
        wsUrl = serverAddress
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@CarryOverClient.webSocket = webSocket
            Log.d("CarryOverClient", "[client:$deviceId] Connected to server.")
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
                handleMessage(JSONObject(text))
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

    private fun handleMessage(rawMsg: JSONObject) {
        if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Received (raw): $rawMsg")

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
                if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Decrypted message: $decoded")
                decoded
            } catch (e: Exception) {
                Log.d("CarryOverClient", "[client:$deviceId] Failed to decrypt message: ${e.localizedMessage}")
                return
            }
        } else {
            rawMsg
        }

        val msgType = effectiveMsg.optString("type", "")

        handleResponseTypes(msgType, effectiveMsg)
    }

    private fun handleResponseTypes(msgType: String, effectiveMsg: JSONObject) {
        val msgId = effectiveMsg.optString("msg_id", "")
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

                // Flush pending messages (JSON and raw)
                if (pendingJsonMessages.isNotEmpty()) {
                    if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Sending ${pendingJsonMessages.size} queued JSON messages")
                    val queued = pendingJsonMessages.toList()
                    pendingJsonMessages.clear()
                    queued.forEach { sendEncrypted(it) }
                }
                if (pendingRawMessages.isNotEmpty()) {
                    if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Sending ${pendingRawMessages.size} queued raw messages")
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

                sendEncrypted(JSONObject().apply {
                    put("type", "DELIVERY_ACK")
                    put("msg_id", msgId)
                    put("status", "delivered")
                    put("ts", System.currentTimeMillis())
                    put("enc", true)
                })
            }

            "SEND_NODST" -> {
                Log.d("CarryOverClient", "[client:$deviceId] Message not sent to: ${effectiveMsg.getString("dst")}. Device is offline.")
            }

            "SEND_FAIL" -> {
                Log.d("CarryOverClient", "[client:$deviceId] Message not sent. No devices are online.")
            }

            "SEND_OK" -> {
                if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Message received by the server.")
            }

            "DELIVERY_ACK_FOR_SENDER" -> {
                Log.d("CarryOverClient", "[client:$deviceId] Message received by: ${effectiveMsg.optString("from", "unknown")}.")
                webSocket?.close(1000, "Normal Closure.")
                Log.d("CarryOverClient", "[client:$deviceId] Closed connection.")
            }

            "ACK_OK" -> {
                if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Sender received ACK for: $msgId.")
            }

            "ACK_ERROR" -> {
                Log.w("CarryOverClient", "[client:$deviceId] Sender didn't receive ACK for: $msgId. Sender is offline or unknown.")
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

    private fun createMessageId(): String {
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        return bytes.toHexString()
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