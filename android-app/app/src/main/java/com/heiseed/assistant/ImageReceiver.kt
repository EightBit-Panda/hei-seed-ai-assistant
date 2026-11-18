package com.heiseed.assistant

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ImageReceiver(
    context: Context,
    private val onImageReceived: (File) -> Unit,
    private val onHeartbeatReceived: () -> Unit = {}
) {

    companion object {
        private const val TAG = "ImageReceiver"
        private const val PORT = 8080
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024
        private const val MAX_HEADER_LINE_LENGTH = 8192

        private const val NONCE_HEADER = "x-heiseed-nonce"
        private const val HASH_HEADER = "x-heiseed-content-sha256"
        private const val AUTH_HEADER = "x-heiseed-auth"

        private const val NONCE_HEX_LENGTH = 32
        private const val SHA256_HEX_LENGTH = 64
        private const val HMAC_HEX_LENGTH = 64

        private const val MAX_REMEMBERED_NONCES = 2048

        /*
         * This cache prevents a captured request from being accepted again
         * while this app process remains alive. It is intentionally bounded.
         */
        private val usedNonces = LinkedHashSet<String>()
    }

    private val appContext = context.applicationContext
    private val deviceAuthManager = DeviceAuthManager(appContext)

    private val executor = Executors.newFixedThreadPool(3)
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        running = true

        executor.execute {
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "Authenticated image receiver started on port $PORT")

                while (running) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Server error", e)
                }
            } finally {
                running = false
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                    // Socket may already be closed.
                }
                serverSocket = null
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 10000

                if (!isClientOnReceivingSubnet(client)) {
                    Log.w(
                        TAG,
                        "Rejected connection outside receiving private subnet: " +
                                "remote=${client.inetAddress.hostAddress}, " +
                                "local=${client.localAddress.hostAddress}"
                    )
                    sendResponse(client.getOutputStream(), 403, "Forbidden")
                    return
                }

                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()

                val requestLine = readLine(input)
                val isUploadRequest = requestLine == "POST /upload HTTP/1.1" ||
                        requestLine == "POST /upload HTTP/1.0"
                val isHeartbeatRequest = requestLine == "POST /heartbeat HTTP/1.1" ||
                        requestLine == "POST /heartbeat HTTP/1.0"

                if (!isUploadRequest && !isHeartbeatRequest) {
                    sendResponse(output, 404, "Not Found")
                    return
                }

                val headers = readHeaders(input)

                if (isHeartbeatRequest) {
                    handleHeartbeat(output, headers)
                    return
                }

                val contentLengthText = headers["content-length"]
                val contentType = headers["content-type"].orEmpty()
                val nonce = headers[NONCE_HEADER]
                val suppliedImageHash = headers[HASH_HEADER]
                val suppliedHmac = headers[AUTH_HEADER]

                val contentLength = contentLengthText?.toIntOrNull() ?: -1

                if (contentLength <= 0 || contentLength > MAX_IMAGE_SIZE) {
                    sendResponse(output, 400, "Invalid image size")
                    return
                }

                if (contentType.substringBefore(';').trim()
                        .equals("image/jpeg", ignoreCase = true).not()
                ) {
                    sendResponse(output, 415, "JPEG required")
                    return
                }

                if (nonce == null || suppliedImageHash == null || suppliedHmac == null) {
                    sendResponse(output, 401, "Authentication required")
                    return
                }

                if (!isHex(nonce, NONCE_HEX_LENGTH) ||
                    !isHex(suppliedImageHash, SHA256_HEX_LENGTH) ||
                    !isHex(suppliedHmac, HMAC_HEX_LENGTH)
                ) {
                    sendResponse(output, 401, "Invalid authentication headers")
                    return
                }

                val deviceKeyHex = try {
                    deviceAuthManager.getDeviceKey()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not read device authentication key", e)
                    null
                }

                if (deviceKeyHex == null || !isHex(deviceKeyHex, 64)) {
                    Log.e(TAG, "No valid device key is configured in the app")
                    sendResponse(output, 503, "Device authentication is not configured")
                    return
                }

                val keyBytes = hexToBytes(deviceKeyHex)
                val nonceKey = nonce.lowercase()

                if (isNonceAlreadyUsed(nonceKey)) {
                    sendResponse(output, 401, "Nonce already used")
                    return
                }

                /*
                 * This canonical message must match device_auth.c exactly:
                 * POST\n/upload\n<nonce>\n<content-length>\n<image-sha256>
                 */
                val canonicalMessage = buildString {
                    append("POST\n")
                    append("/upload\n")
                    append(nonce)
                    append('\n')
                    append(contentLength)
                    append('\n')
                    append(suppliedImageHash)
                }

                val hmacIsValid = try {
                    val expectedHmac = calculateHmac(
                        keyBytes,
                        canonicalMessage.toByteArray(Charsets.US_ASCII)
                    )
                    val receivedHmac = hexToBytes(suppliedHmac)
                    MessageDigest.isEqual(expectedHmac, receivedHmac)
                } finally {
                    keyBytes.fill(0)
                }

                if (!hmacIsValid) {
                    sendResponse(output, 401, "Authentication failed")
                    return
                }

                /*
                 * Only authenticated requests are written to a temporary file.
                 * The image hash is checked while streaming the body.
                 */
                val imageFile = File(
                    appContext.filesDir,
                    "image_${System.currentTimeMillis()}_${System.nanoTime()}.jpg"
                )

                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(imageFile).use { fileOutput ->
                        val buffer = ByteArray(8192)
                        var remaining = contentLength

                        while (remaining > 0) {
                            val count = input.read(
                                buffer,
                                0,
                                minOf(buffer.size, remaining)
                            )

                            if (count == -1) {
                                throw IllegalStateException("Incomplete image received")
                            }

                            if (count > 0) {
                                digest.update(buffer, 0, count)
                                fileOutput.write(buffer, 0, count)
                                remaining -= count
                            }
                        }

                        fileOutput.flush()
                    }

                    val actualImageHash = digest.digest()
                    val expectedImageHash = hexToBytes(suppliedImageHash)

                    if (!MessageDigest.isEqual(actualImageHash, expectedImageHash)) {
                        imageFile.delete()
                        sendResponse(output, 401, "Image hash verification failed")
                        return
                    }

                    if (!hasJpegMarkers(imageFile)) {
                        imageFile.delete()
                        sendResponse(output, 415, "Invalid JPEG image")
                        return
                    }

                    if (!rememberNonce(nonceKey)) {
                        sendResponse(output, 401, "Nonce already used")
                        return
                    }

                    Log.i(
                        TAG,
                        "Authenticated image saved: ${imageFile.name}, ${imageFile.length()} bytes"
                    )

                    sendResponse(output, 200, "Image received")
                    onImageReceived(imageFile)

                } catch (e: Exception) {
                    imageFile.delete()
                    throw e
                }

            } catch (e: Exception) {
                Log.e(TAG, "Image transfer failed", e)
                try {
                    sendResponse(client.getOutputStream(), 500, "Image transfer failed")
                } catch (_: Exception) {
                    // Connection may already be closed.
                }
            }
        }
    }

    /**
     * A heartbeat proves that the device can reach this app and knows the
     * shared device key. The canonical message must match device_auth.c:
     * POST\\n/heartbeat\\n<nonce>\\n0
     */
    private fun handleHeartbeat(
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val contentLength = headers["content-length"]?.toIntOrNull()
        val nonce = headers[NONCE_HEADER]
        val suppliedHmac = headers[AUTH_HEADER]

        if (contentLength != 0) {
            sendResponse(output, 400, "Heartbeat body must be empty")
            return
        }

        if (nonce == null || suppliedHmac == null ||
            !isHex(nonce, NONCE_HEX_LENGTH) ||
            !isHex(suppliedHmac, HMAC_HEX_LENGTH)
        ) {
            sendResponse(output, 401, "Valid heartbeat authentication required")
            return
        }

        val nonceKey = nonce.lowercase()
        if (isNonceAlreadyUsed(nonceKey)) {
            sendResponse(output, 401, "Nonce already used")
            return
        }

        val deviceKeyHex = try {
            deviceAuthManager.getDeviceKey()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read device authentication key for heartbeat", e)
            null
        }

        if (deviceKeyHex == null || !isHex(deviceKeyHex, 64)) {
            Log.e(TAG, "No valid device key is configured in the app")
            sendResponse(output, 503, "Device authentication is not configured")
            return
        }

        val keyBytes = hexToBytes(deviceKeyHex)
        val canonicalMessage = "POST\n/heartbeat\n$nonce\n0"
        val hmacIsValid = try {
            val expectedHmac = calculateHmac(
                keyBytes,
                canonicalMessage.toByteArray(Charsets.US_ASCII)
            )
            val receivedHmac = hexToBytes(suppliedHmac)
            MessageDigest.isEqual(expectedHmac, receivedHmac)
        } finally {
            keyBytes.fill(0)
        }

        if (!hmacIsValid) {
            sendResponse(output, 401, "Heartbeat authentication failed")
            return
        }

        if (!rememberNonce(nonceKey)) {
            sendResponse(output, 401, "Nonce already used")
            return
        }

        Log.d(TAG, "Authenticated heartbeat received from Seed")
        sendResponse(output, 200, "Heartbeat received")
        onHeartbeatReceived()
    }

    /**
     * Accept only IPv4 clients in the same private subnet as the local
     * interface that received this connection. With the phone acting as
     * the hotspot, ESP32 connects to the phone's hotspot/gateway address,
     * so the accepted socket's local address identifies that interface.
     *
     * This is a network-scope restriction, not device authentication.
     * HMAC verification remains mandatory because other clients on the
     * same hotspot subnet may still be able to connect.
     */
    private fun isClientOnReceivingSubnet(socket: Socket): Boolean {
        return try {
            val localAddress = socket.localAddress as? Inet4Address ?: return false
            val remoteAddress = socket.inetAddress as? Inet4Address ?: return false

            if (!isPrivateIpv4(localAddress.address)) return false

            val receivingInterface = NetworkInterface.getByInetAddress(localAddress)
                ?: return false

            if (!receivingInterface.isUp) return false

            receivingInterface.interfaceAddresses.any { interfaceAddress ->
                val interfaceIpv4 = interfaceAddress.address as? Inet4Address
                    ?: return@any false

                if (!interfaceIpv4.address.contentEquals(localAddress.address)) {
                    return@any false
                }

                val prefixLength = interfaceAddress.networkPrefixLength.toInt()
                prefixLength in 1..32 &&
                        isSameIpv4Subnet(
                            remoteAddress.address,
                            interfaceIpv4.address,
                            prefixLength
                        )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify receiving network interface", e)
            false
        }
    }

    private fun isPrivateIpv4(address: ByteArray): Boolean {
        if (address.size != 4) return false

        val first = address[0].toInt() and 0xFF
        val second = address[1].toInt() and 0xFF

        return first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
    }

    private fun isSameIpv4Subnet(
        firstAddress: ByteArray,
        secondAddress: ByteArray,
        prefixLength: Int
    ): Boolean {
        if (firstAddress.size != 4 || secondAddress.size != 4) return false
        if (prefixLength !in 1..32) return false

        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8

        for (index in 0 until fullBytes) {
            if (firstAddress[index] != secondAddress[index]) return false
        }

        if (remainingBits == 0) return true

        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        val firstPart = firstAddress[fullBytes].toInt() and 0xFF
        val secondPart = secondAddress[fullBytes].toInt() and 0xFF

        return (firstPart and mask) == (secondPart and mask)
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()

        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break

            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw IllegalStateException("Malformed HTTP header")
            }

            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()

            if (name.isEmpty() || headers.containsKey(name)) {
                throw IllegalStateException("Duplicate or invalid HTTP header")
            }

            headers[name] = value
        }

        if (headers.containsKey("transfer-encoding")) {
            throw IllegalStateException("Transfer-Encoding is not supported")
        }

        return headers
    }

    private fun readLine(input: BufferedInputStream): String {
        val line = StringBuilder()

        while (true) {
            val value = input.read()

            if (value == -1) {
                if (line.isEmpty()) {
                    throw IllegalStateException("Unexpected end of HTTP request")
                }
                break
            }

            if (value == '\n'.code) break

            if (value != '\r'.code) {
                line.append(value.toChar())
            }

            if (line.length > MAX_HEADER_LINE_LENGTH) {
                throw IllegalStateException("HTTP header line too large")
            }
        }

        return line.toString()
    }

    private fun isHex(value: String, expectedLength: Int): Boolean {
        return value.length == expectedLength &&
                value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex string must have even length" }

        return ByteArray(value.length / 2) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun calculateHmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun hasJpegMarkers(file: File): Boolean {
        if (file.length() < 4) return false

        file.inputStream().use { stream ->
            val first = stream.read()
            val second = stream.read()

            if (first != 0xFF || second != 0xD8) {
                return false
            }

            var bytesToSkip = file.length() - 4
            while (bytesToSkip > 0) {
                val skipped = stream.skip(bytesToSkip)
                if (skipped <= 0) {
                    if (stream.read() == -1) return false
                    bytesToSkip--
                } else {
                    bytesToSkip -= skipped
                }
            }

            val penultimate = stream.read()
            val last = stream.read()
            return penultimate == 0xFF && last == 0xD9
        }
    }

    private fun isNonceAlreadyUsed(nonce: String): Boolean {
        synchronized(usedNonces) {
            return usedNonces.contains(nonce)
        }
    }

    private fun rememberNonce(nonce: String): Boolean {
        synchronized(usedNonces) {
            if (usedNonces.contains(nonce)) return false

            usedNonces.add(nonce)

            while (usedNonces.size > MAX_REMEMBERED_NONCES) {
                val oldest = usedNonces.iterator()
                if (oldest.hasNext()) {
                    usedNonces.remove(oldest.next())
                } else {
                    break
                }
            }

            return true
        }
    }

    private fun sendResponse(
        output: OutputStream,
        status: Int,
        message: String
    ) {
        val body = message.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            415 -> "Unsupported Media Type"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }

        val response = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        output.write(response.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    fun stop() {
        running = false

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server", e)
        }

        serverSocket = null
    }
}
