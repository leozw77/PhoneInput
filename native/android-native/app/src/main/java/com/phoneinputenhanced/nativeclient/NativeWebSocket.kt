package com.phoneinputenhanced.nativeclient

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class NativeWebSocket(
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: State, detail: String = "")
        fun onProtocolMessage(message: String)
    }

    enum class State { Disconnected, Connecting, Connected, Reconnecting }

    data class DiagnosticsSnapshot(
        val state: State,
        val detail: String,
        val host: String,
        val serverVersion: String,
        val reconnectCount: Long,
        val commandsWritten: Long,
        val moveCommandsWritten: Long,
        val scrollCommandsWritten: Long,
        val writerQueueDepth: Int,
        val lastAckAgeMs: Long,
        val lastHeartbeatAgeMs: Long,
        val connectedForMs: Long,
        val lastError: String,
        val waitingForNetwork: Boolean,
        val voiceHotkeyCount: Long,
        val lastVoiceHotkeyAgeMs: Long,
        val lastVoiceAck: String,
    )

    private val worker = Executors.newSingleThreadExecutor()
    // All post-handshake socket writes stay off Android's main/UI thread.
    // Exposing the queue depth makes diagnostics useful when pointer latency appears.
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
    )
    private val main = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()
    private val requestCounter = AtomicLong(0)
    private val writeLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var host: String = ""
    @Volatile private var shouldReconnect = false
    @Volatile private var generation = 0L
    @Volatile private var helloRequestId: String? = null
    @Volatile private var connected = false
    @Volatile private var currentState = State.Disconnected
    @Volatile private var currentDetail = ""
    @Volatile private var serverVersion = ""
    @Volatile private var lastError = ""
    @Volatile private var waitingForNetwork = false
    private val reconnectCount = AtomicLong(0)
    private val commandsWritten = AtomicLong(0)
    private val moveCommandsWritten = AtomicLong(0)
    private val scrollCommandsWritten = AtomicLong(0)
    private val lastAckElapsed = AtomicLong(0)
    private val lastHeartbeatElapsed = AtomicLong(0)
    private val connectedAtElapsed = AtomicLong(0)
    private val voiceHotkeyCount = AtomicLong(0)
    private val lastVoiceHotkeyElapsed = AtomicLong(0)
    @Volatile private var lastVoiceRequestId = ""
    @Volatile private var lastVoiceAck = "n/a"

    private val heartbeat = object : Runnable {
        override fun run() {
            if (connected && shouldReconnect) {
                sendCommand("ping")
                main.postDelayed(this, 15_000)
            }
        }
    }

    fun isConnected(): Boolean = connected

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        val now = SystemClock.elapsedRealtime()
        fun age(value: Long): Long = if (value <= 0L) -1L else (now - value).coerceAtLeast(0L)
        return DiagnosticsSnapshot(
            state = currentState,
            detail = currentDetail,
            host = host,
            serverVersion = serverVersion,
            reconnectCount = reconnectCount.get(),
            commandsWritten = commandsWritten.get(),
            moveCommandsWritten = moveCommandsWritten.get(),
            scrollCommandsWritten = scrollCommandsWritten.get(),
            writerQueueDepth = writer.queue.size,
            lastAckAgeMs = age(lastAckElapsed.get()),
            lastHeartbeatAgeMs = age(lastHeartbeatElapsed.get()),
            connectedForMs = if (connected) age(connectedAtElapsed.get()) else -1L,
            lastError = lastError,
            waitingForNetwork = waitingForNetwork,
            voiceHotkeyCount = voiceHotkeyCount.get(),
            lastVoiceHotkeyAgeMs = age(lastVoiceHotkeyElapsed.get()),
            lastVoiceAck = lastVoiceAck,
        )
    }

    fun onNetworkLost() {
        if (!shouldReconnect) return
        waitingForNetwork = true
        connected = false
        ++generation
        closeSocket()
        main.removeCallbacks(heartbeat)
        postState(State.Reconnecting, "Wi-Fi/局域网连接已断开")
    }

    fun onNetworkAvailable() {
        if (!shouldReconnect || !waitingForNetwork) return
        waitingForNetwork = false
        val token = ++generation
        closeSocket()
        postState(State.Reconnecting, "网络恢复，正在重连")
        worker.execute { runConnection(token, true) }
    }

    fun onAppForeground() {
        if (!shouldReconnect || connected || waitingForNetwork) return
        if (currentState == State.Connecting) return
        val token = ++generation
        closeSocket()
        postState(State.Reconnecting, "返回前台，正在确认连接")
        worker.execute { runConnection(token, true) }
    }

    fun shutdown() {
        disconnect()
        worker.shutdownNow()
        writer.shutdownNow()
    }

    fun connect(rawHost: String) {
        val normalized = normalizeHost(rawHost)
        if (normalized.isEmpty()) {
            postState(State.Disconnected, "请输入电脑 IP")
            return
        }
        host = normalized
        shouldReconnect = true
        waitingForNetwork = false
        val token = ++generation
        closeSocket()
        postState(State.Connecting, "$normalized:${ProtocolV2.PORT}")
        worker.execute { runConnection(token, false) }
    }

    fun disconnect() {
        // Closing the last control socket makes the Windows host release held buttons.
        // Do not write a release frame here because lifecycle callbacks run on the UI thread.
        shouldReconnect = false
        connected = false
        waitingForNetwork = false
        ++generation
        closeSocket()
        main.removeCallbacks(heartbeat)
        postState(State.Disconnected, "已断开")
    }

    fun releaseAll() {
        if (connected) sendCommand("release")
    }

    fun sendMove(dx: Int, dy: Int) = sendCommand("move", mapOf("dx" to dx, "dy" to dy))
    fun sendScroll(x: Int, y: Int) = sendCommand("scroll", mapOf("x" to x, "y" to y))
    fun click(button: String) = sendCommand("click", mapOf("button" to button))
    fun button(button: String, down: Boolean) = sendCommand("button", mapOf("button" to button, "down" to down))
    fun hotkey(action: String) = sendCommand("hotkey", mapOf("action" to action))
    fun key(key: String) = sendCommand("key", mapOf("key" to key))
    fun switchWindow(target: String) = sendCommand("window_switch", mapOf("target" to target))

    fun sendCommand(type: String, fields: Map<String, Any?> = emptyMap()) {
        if (!connected && type != "hello") {
            postMessage("未连接电脑")
            return
        }
        val token = generation
        val requestId = nextRequestId(type)
        if (type == "hotkey" && fields["action"] == "voice") {
            lastVoiceRequestId = requestId
            lastVoiceAck = "pending"
            voiceHotkeyCount.incrementAndGet()
            lastVoiceHotkeyElapsed.set(SystemClock.elapsedRealtime())
        }
        val payload = ProtocolV2.command(type, requestId, fields).toString()
        writer.execute {
            if (token != generation || (!connected && type != "hello")) return@execute
            try {
                writeTextFrame(payload)
                commandsWritten.incrementAndGet()
                if (type == "move") moveCommandsWritten.incrementAndGet()
                if (type == "scroll") scrollCommandsWritten.incrementAndGet()
                if (type == "ping") lastHeartbeatElapsed.set(SystemClock.elapsedRealtime())
            } catch (error: Exception) {
                if (token == generation) {
                    handleDisconnect(token, "发送失败：${describeNetworkError(error)}")
                }
            }
        }
    }

    private fun runConnection(token: Long, reconnecting: Boolean) {
        var local: Socket? = null
        try {
            if (reconnecting) postState(State.Reconnecting, "$host:${ProtocolV2.PORT}")
            local = Socket()
            local.tcpNoDelay = true
            local.keepAlive = true
            local.connect(InetSocketAddress(host, ProtocolV2.PORT), 2500)
            local.soTimeout = 45_000
            val input = BufferedInputStream(local.getInputStream())
            val out = BufferedOutputStream(local.getOutputStream())
            performHandshake(input, out, host)
            if (token != generation || !shouldReconnect) {
                local.close()
                return
            }
            socket = local
            output = out
            connected = false
            val helloId = nextRequestId("hello")
            helloRequestId = helloId
            writeTextFrame(ProtocolV2.command("hello", helloId, mapOf(
                "client" to "android-native",
                "version" to BuildConfig.VERSION_NAME,
            )).toString())
            readLoop(token, input, out)
        } catch (error: Exception) {
            if (token == generation && shouldReconnect) {
                handleDisconnect(token, describeNetworkError(error))
            }
        } finally {
            if (socket === local) {
                socket = null
                output = null
            }
            try { local?.close() } catch (_: Exception) {}
        }
    }

    private fun readLoop(token: Long, input: BufferedInputStream, out: BufferedOutputStream) {
        while (token == generation && shouldReconnect) {
            val frame = readFrame(input)
            when (frame.opcode) {
                0x1 -> handleText(frame.payload.toString(StandardCharsets.UTF_8))
                0x8 -> throw EOFException("电脑端关闭连接")
                0x9 -> writeControlFrame(out, 0xA, frame.payload)
                0xA -> Unit
                else -> throw IllegalStateException("不支持的 WebSocket frame")
            }
        }
    }

    private fun handleText(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        if (json.optString("type") != "ack") return
        val requestId = json.optString("requestId")
        val ok = json.optBoolean("ok", false)
        lastAckElapsed.set(SystemClock.elapsedRealtime())
        if (requestId == lastVoiceRequestId) {
            lastVoiceAck = if (ok) "ok" else {
                val error = json.optJSONObject("error")
                val code = error?.optString("code").orEmpty()
                val message = error?.optString("message").orEmpty()
                listOf(code, message).filter { it.isNotBlank() }.joinToString(": ").ifBlank { "failed" }
            }
        }
        if (requestId == helloRequestId) {
            if (ok) {
                connected = true
                serverVersion = json.optString("serverVersion", "v2")
                connectedAtElapsed.set(SystemClock.elapsedRealtime())
                lastAckElapsed.set(SystemClock.elapsedRealtime())
                lastError = ""
                postState(State.Connected, serverVersion)
                main.removeCallbacks(heartbeat)
                main.postDelayed(heartbeat, 15_000)
            } else {
                val error = json.optJSONObject("error")?.optString("message") ?: "协议握手失败"
                throw IllegalStateException(error)
            }
            return
        }
        if (!ok) {
            val error = json.optJSONObject("error")
            val code = error?.optString("code").orEmpty()
            val message = error?.optString("message").orEmpty()
            postMessage(if (code.isNotEmpty()) "$message ($code)" else message.ifEmpty { "操作失败" })
        }
    }

    private fun nextRequestId(prefix: String): String = "$prefix-${requestCounter.incrementAndGet()}"

    private fun performHandshake(input: BufferedInputStream, out: BufferedOutputStream, host: String) {
        val keyBytes = ByteArray(16).also(secureRandom::nextBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET ${ProtocolV2.PATH} HTTP/1.1\r\n")
            append("Host: $host:${ProtocolV2.PORT}\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: PhoneInputEnhanced/${BuildConfig.VERSION_NAME} AndroidNative\r\n")
            append("\r\n")
        }
        out.write(request.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        val header = readHttpHeader(input)
        val lines = header.split("\r\n")
        if (lines.isEmpty() || !lines[0].contains(" 101 ")) {
            throw IllegalStateException("电脑端未提供 Native Protocol v2")
        }
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase(Locale.US) to line.substring(index + 1).trim()
        }.toMap()
        val expected = websocketAccept(key)
        if (!headers["sec-websocket-accept"].equals(expected, ignoreCase = false)) {
            throw IllegalStateException("WebSocket 握手校验失败")
        }
    }

    private fun websocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun readHttpHeader(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (buffer.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) throw EOFException("握手响应中断")
            buffer.write(value)
            if (value.toByte() == terminator[matched]) {
                matched++
                if (matched == terminator.size) return buffer.toString(StandardCharsets.US_ASCII.name())
            } else {
                matched = if (value.toByte() == terminator[0]) 1 else 0
            }
        }
        throw IllegalStateException("握手响应过大")
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: BufferedInputStream): Frame {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0) throw EOFException("连接已断开")
        if ((first and 0x80) == 0) throw IllegalStateException("不支持分片 frame")
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = readUnsigned(input, 2)
        if (length == 127L) length = readUnsigned(input, 8)
        if (length > 64 * 1024) throw IllegalStateException("服务端 frame 过大")
        val mask = if (masked) readExact(input, 4) else null
        val payload = readExact(input, length.toInt())
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, payload)
    }

    private fun readUnsigned(input: BufferedInputStream, count: Int): Long {
        var value = 0L
        repeat(count) {
            val next = input.read()
            if (next < 0) throw EOFException("frame 长度中断")
            value = (value shl 8) or next.toLong()
        }
        return value
    }

    private fun readExact(input: BufferedInputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("frame 数据中断")
            offset += read
        }
        return result
    }

    private fun writeTextFrame(text: String) {
        val out = output ?: throw IllegalStateException("连接不可用")
        writeClientFrame(out, 0x1, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeControlFrame(out: BufferedOutputStream, opcode: Int, payload: ByteArray) {
        writeClientFrame(out, opcode, payload)
    }

    private fun writeClientFrame(out: BufferedOutputStream, opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val mask = ByteArray(4).also(secureRandom::nextBytes)
            out.write(0x80 or opcode)
            when {
                payload.size <= 125 -> out.write(0x80 or payload.size)
                payload.size <= 65535 -> {
                    out.write(0x80 or 126)
                    out.write((payload.size ushr 8) and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    val n = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) out.write(((n ushr shift) and 0xFF).toInt())
                }
            }
            out.write(mask)
            val masked = ByteArray(payload.size)
            for (i in payload.indices) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            out.write(masked)
            out.flush()
        }
    }

    private fun handleDisconnect(token: Long, detail: String) {
        if (token != generation) return
        connected = false
        lastError = detail
        closeSocket()
        main.removeCallbacks(heartbeat)
        if (!shouldReconnect) {
            postState(State.Disconnected, detail)
            return
        }
        if (waitingForNetwork) {
            postState(State.Reconnecting, "等待局域网恢复 · $detail")
            return
        }
        reconnectCount.incrementAndGet()
        postState(State.Reconnecting, detail)
        val reconnectToken = ++generation
        main.postDelayed({
            if (reconnectToken == generation && shouldReconnect && !waitingForNetwork) {
                worker.execute { runConnection(reconnectToken, true) }
            }
        }, 1500)
    }

    private fun closeSocket() {
        val old = socket
        socket = null
        output = null
        try { old?.close() } catch (_: Exception) {}
    }

    private fun postState(state: State, detail: String) {
        currentState = state
        currentDetail = detail
        main.post { listener.onStateChanged(state, detail) }
    }
    private fun postMessage(message: String) = main.post { listener.onProtocolMessage(message) }

    private fun describeNetworkError(error: Throwable): String {
        val name = error.javaClass.simpleName.ifBlank { "NetworkError" }
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) name else "$name: $message"
    }

    private fun normalizeHost(raw: String): String {
        var value = raw.trim()
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("ws://").removePrefix("wss://")
        value = value.substringBefore('/').substringBefore(':')
        return value.trim()
    }
}
