package dev.lukassobotik.fossqol.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.lukassobotik.fossqol.SHARED_PREFERENCES_PAIRED_DEVICES
import dev.lukassobotik.fossqol.SHARED_PREFERENCES_PAIRED_DEVICE_IDS
import dev.lukassobotik.fossqol.SHARED_PREFERENCES_SERVER_IP
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

// TODO: Handle unsupported versions
@RequiresApi(Build.VERSION_CODES.O)
class CarryOverClient(private val context: Context) {
    private var wsUrl: String = "" //ws://10.0.2.2:6778/ws
    private var pairedDevices: JSONArray = JSONArray()

    private val verboseLogging: Boolean = false

    // DER header used in Node.js ("302a300506032b656e032100" hex)
    private val X25519_SPKE_DER_HEADER = hexStringToByteArray("302a300506032b656e032100")

    private val PREFS_E2E_PUB = "carryover_e2e_pub"   // stores Base64 SPKI (public key bytes)
    private val PREFS_E2E_PRIV = "carryover_e2e_priv" // stores Base64 PKCS8 (private key bytes)

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

        // If this is a SEND containing "to", handle per-recipient E2EE
        if (obj.optString("type", "") == "SEND") {
            encryptSendMessage(obj, key, ws)
            return
        }

        // Non-SEND messages: encrypt as before (single envelope)
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

    private fun encryptSendMessage(obj: JSONObject, key: ByteArray?, ws: WebSocket?) {
        val toArr = obj.optJSONArray("to") ?: JSONArray()
        val payloadPlain = obj.opt("payload_plain")
        // iterate per recipient and send one SEND per recipient
        for (i in 0 until toArr.length()) {
            val dst = toArr.optString(i)
            val recipientPub = recipientPubEncFor(dst)
            if (recipientPub != null && payloadPlain != null) {
                // create inner JSON with msg_id/from/payload
                val msgId = obj.optString("msg_id", createMessageId())
                val inner = JSONObject()
                inner.put("msg_id", msgId)
                inner.put("from", deviceId)
                inner.put("ts", System.currentTimeMillis() / 1000)
                inner.put("payload", payloadPlain)

                // create sealed payload (ephemeral -> recipient)
                val sealed = createE2EESealedPayload(recipientPub, msgId, deviceId, inner.toString().toByteArray(Charsets.UTF_8))

                // build per-recipient SEND object
                val per = JSONObject()
                per.put("payload_e2e", sealed)
                sendEncryptedSendToPerson(per, obj, msgId, dst, key, ws)
            } else {
                // fallback to plaintext SEND for this recipient
                val msgId = obj.optString("msg_id", createMessageId())
                val per = JSONObject()
                per.put("payload_plain", payloadPlain)
                sendEncryptedSendToPerson(per, obj, msgId, dst, key, ws)
            }
        }
    }

    private fun sendEncryptedSendToPerson(per: JSONObject, obj: JSONObject, msgId: String, dst: String?, key: ByteArray?, ws: WebSocket?) {
        per.put("payload_meta", obj.opt("payload_meta"))
        per.put("type", "SEND")
        per.put("msg_id", msgId)
        per.put("from", deviceId)
        per.put("to", JSONArray().put(dst))
        per.put("ts", System.currentTimeMillis() / 1000)
        // send this per-recipient SEND via session AEAD
        if (key != null && ws != null) {
            val env = encryptSessionObject(per, key)
            ws.send(env.toString())
        } else {
            // queue fallback - preserve as JSON
            pendingJsonMessages.add(per)
        }
    }

    private fun createE2EESealedPayload(recipientPubRawB64: String, msgId: String, fromDevice: String, innerPlaintext: ByteArray): JSONObject {
        val recipientRaw = base64Decoder.decode(recipientPubRawB64)
        // build recipient SPKI then PublicKey instance
        val kf = KeyFactory.getInstance("X25519")
        val recSpki = spkiFromRaw(recipientRaw, X25519_SPKE_DER_HEADER)
        val recipientPubKey = kf.generatePublic(X509EncodedKeySpec(recSpki))

        // ephemeral keypair
        val kpg = java.security.KeyPairGenerator.getInstance("X25519")
        val ephem = kpg.generateKeyPair()
        val ephemPubRaw = (ephem.public.encoded).copyOfRange(ephem.public.encoded.size - 32, ephem.public.encoded.size)

        // derive shared secret
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(ephem.private)
        ka.doPhase(recipientPubKey, true)
        val shared = ka.generateSecret()

        // hkdf info: "carryover-e2e-v1" || msgId || from || to (use msgId and from)
        val info = ByteBuffer.allocate("carryover-e2e-v1".toByteArray().size + msgId.toByteArray().size + fromDevice.toByteArray().size)
            .put("carryover-e2e-v1".toByteArray(Charsets.UTF_8))
            .put(msgId.toByteArray(Charsets.UTF_8))
            .put(fromDevice.toByteArray(Charsets.UTF_8))
            .array()

        val salt = ByteArray(32) // zero salt
        val symKey = hkdfSha256(shared, salt, info, 32) // 32 bytes key for AES-256

        // AES-GCM encrypt
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val ctAndTag = aesGcmEncrypt(symKey, iv, innerPlaintext, null)

        val obj = JSONObject()
        obj.put("ephem_pk", base64.encodeToString(ephemPubRaw))
        obj.put("iv", base64.encodeToString(iv))
        obj.put("ct", base64.encodeToString(ctAndTag))
        return obj
    }

    // returns plaintext bytes or throws
    private fun unsealE2EEPayload(payloadE2E: JSONObject, msgId: String, fromDevice: String): ByteArray {
        val ephemPk = base64Decoder.decode(payloadE2E.getString("ephem_pk"))
        val iv = base64Decoder.decode(payloadE2E.getString("iv"))
        val ct = base64Decoder.decode(payloadE2E.getString("ct"))

        // build ephemeral PublicKey
        val kf = KeyFactory.getInstance("X25519")
        val ephemSpki = spkiFromRaw(ephemPk, X25519_SPKE_DER_HEADER)
        val ephemPubKey = kf.generatePublic(X509EncodedKeySpec(ephemSpki))

        // ensure we have persistent e2e keypair
        val e2eKP = loadOrCreateE2EKeyPair()
        // compute shared = X25519(our_priv, ephem_pub)
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(e2eKP.private)
        ka.doPhase(ephemPubKey, true)
        val shared = ka.generateSecret()

        val info = ByteBuffer.allocate("carryover-e2e-v1".toByteArray().size + msgId.toByteArray().size + fromDevice.toByteArray().size)
            .put("carryover-e2e-v1".toByteArray(Charsets.UTF_8))
            .put(msgId.toByteArray(Charsets.UTF_8))
            .put(fromDevice.toByteArray(Charsets.UTF_8))
            .array()

        val salt = ByteArray(32)
        val symKey = hkdfSha256(shared, salt, info, 32)

        return aesGcmDecrypt(symKey, iv, ct, null)
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
                if (verboseLogging) Log.d("CarryOverClient", "[client:$deviceId] Session key derived: ${sessionKey!!.toHexString()}")

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
                val fromDevice = effectiveMsg.optString("from", "unknown")
                val msgId = effectiveMsg.optString("msg_id", "")
                // check for e2e sealed payload (payload_e2e) or payload_ct_sealed (legacy)
                if (effectiveMsg.has("payload_e2e")) {
                    try {
                        val payloadE2E = effectiveMsg.getJSONObject("payload_e2e")
                        val plaintextBytes = unsealE2EEPayload(payloadE2E, msgId, fromDevice)
                        val inner = JSONObject(String(plaintextBytes, Charsets.UTF_8))
                        Log.d("CarryOverClient", "[client:$deviceId] Unsealed payload: $inner")
                    } catch (e: Exception) {
                        Log.w("CarryOverClient", "[client:$deviceId] Failed to unseal payload: ${e.localizedMessage}")
                    }
                } else if (effectiveMsg.has("payload_ct_sealed")) {
                    Log.w("CarryOverClient", "[client:$deviceId] Received payload_ct_sealed but payload_e2e preferred.")
                } else if (effectiveMsg.has("payload_plain")) {
                    val payload = effectiveMsg.opt("payload_plain")
                    Log.d("CarryOverClient", "[client:$deviceId] Received plain payload: $payload")
                } else {
                    Log.w("CarryOverClient", "[client:$deviceId] FORWARD without known payload")
                }

                // send DELIVERY_ACK (session-encrypted)
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

    // load or generate persistent X25519 keypair used for E2EE inbox (pub_enc)
    private fun loadOrCreateE2EKeyPair(): KeyPair {
        val prefs = context.getSharedPreferences("carryover_prefs", Context.MODE_PRIVATE)
        val pubB64 = prefs.getString(PREFS_E2E_PUB, null)
        val privB64 = prefs.getString(PREFS_E2E_PRIV, null)
        val kf = KeyFactory.getInstance("X25519")

        if (!pubB64.isNullOrEmpty() && !privB64.isNullOrEmpty()) {
            try {
                val pubBytes = base64Decoder.decode(pubB64)
                val privBytes = base64Decoder.decode(privB64)
                val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                val privKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
                return KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                // fall through to regeneration
                Log.w("CarryOverClient", "Failed to load persisted E2EE keypair, regenerating: ${e.localizedMessage}")
            }
        }

        // generate and persist
        val kpg = java.security.KeyPairGenerator.getInstance("X25519")
        val newPair = kpg.generateKeyPair()
        val pubBytes = newPair.public.encoded // X.509/SPKI
        val privBytes = newPair.private.encoded // PKCS#8
        prefs.edit()
            .putString(PREFS_E2E_PUB, base64.encodeToString(pubBytes))
            .putString(PREFS_E2E_PRIV, base64.encodeToString(privBytes))
            .apply()
        return newPair
    }

    private fun recipientPubEncFor(deviceId: String): String? {
        for (i in 0 until pairedDevices.length()) {
            val o = pairedDevices.optJSONObject(i) ?: continue
            if (o.optString("device_id", "") == deviceId) {
                val p = o.optString("pub_enc", "")
                if (p.isNotEmpty()) return p
            }
        }
        return null
    }

    private fun encryptSessionObject(obj: JSONObject, key: ByteArray): JSONObject {
        val msgNonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val ctAndTag = encryptChaCha20Poly1305(key, msgNonce, obj.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().apply {
            put("iv", base64.encodeToString(msgNonce))
            put("ct", base64.encodeToString(ctAndTag))
            put("enc", true)
            put("seq", obj.optInt("seq", messageSeq++))
            put("from_device", obj.optString("from"))
        }
        return envelope
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