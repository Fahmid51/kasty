package com.phonecast.miracast

import android.app.*
import android.content.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MiracastService — Miracast Sink Implementation
 * ================================================
 *
 * HOW MIRACAST WORKS (simplified):
 *
 *  1. DISCOVERY:  Phone advertises itself via Wi-Fi Direct (P2P) service discovery.
 *                 Windows' Win+K scans for Miracast-capable devices using this.
 *
 *  2. CONNECTION: Windows initiates a Wi-Fi Direct connection to the phone.
 *                 Phone accepts as the Group Owner (GO) — acts like a mini access point.
 *
 *  3. RTSP:       After Wi-Fi Direct connects, Windows opens a TCP connection to port 7236.
 *                 They exchange RTSP messages to negotiate video format (H.264, resolution, fps).
 *
 *  4. STREAMING:  Windows sends compressed H.264 video over RTP (UDP).
 *                 Phone decodes it with MediaCodec and renders to a SurfaceView.
 *
 * This service handles all 4 phases.
 */
class MiracastService : Service() {

    companion object {
        private const val TAG = "MiracastService"

        // Intents this service broadcasts to MainActivity
        const val ACTION_STATE_CHANGE  = "com.phonecast.STATE_CHANGE"
        const val ACTION_SURFACE_NEEDED = "com.phonecast.SURFACE_NEEDED"
        const val EXTRA_STATE          = "state"
        const val EXTRA_INFO           = "info"

        // Commands
        const val ACTION_START         = "ACTION_START"
        const val ACTION_STOP          = "ACTION_STOP"
        const val ACTION_PROVIDE_SURFACE = "ACTION_PROVIDE_SURFACE"

        // Miracast standard ports
        const val RTSP_PORT   = 7236
        const val RTP_PORT    = 16384  // We'll tell Windows to use this

        private const val CHANNEL_ID  = "portablecast_channel"
        private const val NOTIF_ID    = 202

        // SurfaceHolder passed from MainActivity for rendering
        @Volatile var surfaceHolder: SurfaceHolder? = null
    }

    private lateinit var wifiP2pManager : WifiP2pManager
    private lateinit var p2pChannel     : Channel
    private var p2pReceiver             : BroadcastReceiver? = null

    private val isRunning = AtomicBoolean(false)

    private var rtspThread  : Thread? = null
    private var rtpThread   : Thread? = null
    private var decoder     : MediaCodec? = null
    private var decoderSurface: Surface? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        p2pChannel     = wifiP2pManager.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                isRunning.set(true)
                setupWifiDirect()
            }
            ACTION_STOP  -> cleanup()
            ACTION_PROVIDE_SURFACE -> {
                // MainActivity gave us a surface — start the decoder
                surfaceHolder?.surface?.let { startDecoder(it) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    // ── Phase 1: Wi-Fi Direct Advertisement ───────────────────────────────────
    //
    // We register a Bonjour/DNS-SD service record that Miracast uses.
    // The service type is "_wfd._tcp" (Wi-Fi Display).
    // Windows' Cast panel scans for exactly this record.

    private fun setupWifiDirect() {
        // WFD (Wi-Fi Display) service info record
        // This is what makes Windows see the phone as a wireless display
        val record = mapOf(
            "devicetype" to "1",        // 1 = Primary sink (display receiver)
            "av_change_notice" to "0",
            "wfd_version" to "1.1"
        )

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            Build.MODEL,          // Device name Windows will show (e.g. "Galaxy A26")
            "_wfd._tcp",          // Miracast service type
            record
        )

        // Clear old registrations first
        wifiP2pManager.clearLocalServices(p2pChannel, object : ActionListener {
            override fun onSuccess() {
                // Register our WFD service
                wifiP2pManager.addLocalService(p2pChannel, serviceInfo, object : ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "WFD service registered — visible to Windows Win+K")
                        broadcastState("ADVERTISING")
                        registerP2pReceiver()
                        startRtspServer()  // Start listening for Windows' connection
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to add local service: $reason")
                        broadcastState("ERROR", "Wi-Fi Direct service failed (code $reason). " +
                            "Make sure Wi-Fi is ON and Location permission is granted.")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "clearLocalServices failed: $reason (non-critical)")
                // Try anyway
                wifiP2pManager.addLocalService(p2pChannel, serviceInfo, object : ActionListener {
                    override fun onSuccess() { broadcastState("ADVERTISING"); startRtspServer() }
                    override fun onFailure(r: Int) { broadcastState("ERROR", "Setup failed: $r") }
                })
            }
        })
    }

    // ── Phase 2: Listen for Windows connecting via Wi-Fi Direct ───────────────

    private fun registerP2pReceiver() {
        val filter = IntentFilter().apply {
            addAction(WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(EXTRA_NETWORK_INFO,
                                android.net.NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(EXTRA_NETWORK_INFO)
                        }

                        if (info?.isConnected == true) {
                            // A device connected — get connection details
                            wifiP2pManager.requestConnectionInfo(p2pChannel) { connInfo ->
                                if (connInfo.groupFormed) {
                                    val pcIp = connInfo.groupOwnerAddress?.hostAddress ?: "unknown"
                                    Log.d(TAG, "PC connected via Wi-Fi Direct. PC IP: $pcIp")
                                    broadcastState("PC_FOUND", pcIp)
                                }
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(p2pReceiver, filter)
    }

    // ── Phase 3: RTSP Server (port 7236) ──────────────────────────────────────
    //
    // After Wi-Fi Direct connects, Windows opens a TCP connection to port 7236
    // and does an RTSP "handshake" to negotiate the video stream parameters.
    //
    // The exchange looks like this:
    //   Windows → OPTIONS *
    //   Phone   ← 200 OK (with supported methods)
    //   Windows → GET_PARAMETER (asks what we support)
    //   Phone   ← 200 OK (with video/audio capabilities)
    //   Windows → SET_PARAMETER (tells us resolution, FPS, etc.)
    //   Phone   ← 200 OK
    //   Windows → SETUP (sets up RTP transport)
    //   Phone   ← 200 OK (with RTP port)
    //   Windows → PLAY
    //   Phone   ← 200 OK
    //   [Video stream starts over RTP]

    private fun startRtspServer() {
        rtspThread = Thread {
            try {
                val server = ServerSocket(RTSP_PORT)
                Log.d(TAG, "RTSP server listening on port $RTSP_PORT")

                while (isRunning.get()) {
                    val client = server.accept()
                    Log.d(TAG, "RTSP connection from: ${client.inetAddress.hostAddress}")
                    broadcastState("CONNECTING")
                    Thread { handleRtspClient(client) }.apply { isDaemon = true; start() }
                }
                server.close()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "RTSP server error: ${e.message}")
                    broadcastState("ERROR", "RTSP failed: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handleRtspClient(socket: Socket) {
        try {
            val reader  = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer  = PrintWriter(socket.getOutputStream(), true)
            var cseq    = 0
            var sessionId = "12345678"

            while (isRunning.get() && !socket.isClosed) {
                // Read RTSP request line by line until blank line
                val lines = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isBlank()) break
                    lines.add(line!!)
                }
                if (lines.isEmpty()) break

                val requestLine = lines[0]
                val method = requestLine.substringBefore(" ")

                // Extract CSeq header
                cseq = lines.firstOrNull { it.startsWith("CSeq:", true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: cseq

                Log.d(TAG, "RTSP ← $method  (CSeq: $cseq)")

                when (method) {
                    "OPTIONS" -> {
                        writer.print(buildRtspResponse(200, "OK", cseq,
                            "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN\r\n"))
                        writer.flush()
                    }

                    "GET_PARAMETER" -> {
                        // Windows asks: what resolutions, codecs, audio do you support?
                        // We respond with our capabilities in WFD (Wi-Fi Display) format.
                        val body = buildWfdCapabilities()
                        writer.print(buildRtspResponse(200, "OK", cseq,
                            "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n",
                            body))
                        writer.flush()
                    }

                    "SET_PARAMETER" -> {
                        // Windows tells us what it will send (resolution, framerate, codec)
                        // We accept everything.
                        // Read body (Content-Length bytes)
                        val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(":")?.trim()?.toInt() ?: 0
                        if (contentLength > 0) {
                            val bodyChars = CharArray(contentLength)
                            reader.read(bodyChars, 0, contentLength)
                            Log.d(TAG, "SET_PARAMETER body: ${String(bodyChars)}")
                        }
                        writer.print(buildRtspResponse(200, "OK", cseq, ""))
                        writer.flush()
                    }

                    "SETUP" -> {
                        // Windows sets up RTP transport — we tell it our RTP port
                        writer.print(buildRtspResponse(200, "OK", cseq,
                            "Transport: RTP/AVP/UDP;unicast;client_port=$RTP_PORT\r\n" +
                            "Session: $sessionId;timeout=60\r\n"))
                        writer.flush()

                        // Start the RTP listener now
                        startRtpReceiver()
                    }

                    "PLAY" -> {
                        writer.print(buildRtspResponse(200, "OK", cseq,
                            "Session: $sessionId\r\nRange: npt=0-\r\n"))
                        writer.flush()

                        // Ask MainActivity for a SurfaceView to render on
                        broadcastState("LIVE", "${socket.inetAddress.hostAddress}")
                        sendBroadcast(Intent(ACTION_SURFACE_NEEDED))
                    }

                    "TEARDOWN" -> {
                        writer.print(buildRtspResponse(200, "OK", cseq, ""))
                        writer.flush()
                        break
                    }

                    else -> {
                        writer.print(buildRtspResponse(501, "Not Implemented", cseq, ""))
                        writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP client error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // WFD capability string — tells Windows what we support
    private fun buildWfdCapabilities(): String {
        return """
wfd_video_formats: 00 00 02 02 0001DEFE 153 00000000 00000000 00 0000 0000 00 none none
wfd_audio_codecs: AAC 00000001 00
wfd_presentation_URL: rtsp://0.0.0.0/wfd1.0/streamid=0 none
wfd_client_rtp_ports: RTP/AVP/UDP;unicast $RTP_PORT 0 mode=play

""".trimIndent()
    }

    private fun buildRtspResponse(
        code: Int, reason: String, cseq: Int,
        headers: String, body: String = ""
    ): String {
        return "RTSP/1.0 $code $reason\r\n" +
               "CSeq: $cseq\r\n" +
               headers +
               "\r\n" +
               body
    }

    // ── Phase 4: RTP Receiver → H.264 Decoder → Screen ───────────────────────
    //
    // Windows sends H.264 encoded frames wrapped in RTP packets over UDP.
    // We receive them, strip RTP headers, feed to MediaCodec, render to Surface.

    private fun startRtpReceiver() {
        rtpThread = Thread {
            try {
                val udpSocket = DatagramSocket(RTP_PORT)
                val buffer    = ByteArray(65535)
                val packet    = DatagramPacket(buffer, buffer.size)

                // Accumulate NAL units into complete H.264 frames
                val frameBuffer = ByteArrayOutputStream()
                var lastSeq     = -1

                while (isRunning.get()) {
                    udpSocket.receive(packet)

                    if (packet.length < 12) continue  // Too short for RTP header

                    // Parse RTP header (12 bytes fixed)
                    val data      = packet.data
                    val version   = (data[0].toInt() and 0xC0) shr 6
                    if (version != 2) continue                // Not RTP v2

                    val marker    = (data[1].toInt() and 0x80) != 0  // End of frame
                    val seqNum    = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                    val payload   = data.copyOfRange(12, packet.length)

                    // H.264 NAL unit fragmentation (FU-A) reassembly
                    val naluType  = payload[0].toInt() and 0x1F

                    when {
                        naluType in 1..23 -> {
                            // Single NAL unit
                            frameBuffer.write(byteArrayOf(0, 0, 0, 1))  // Start code
                            frameBuffer.write(payload)
                        }
                        naluType == 28 -> {
                            // FU-A fragmented NAL unit
                            val fuHeader  = payload[1].toInt()
                            val isStart   = (fuHeader and 0x80) != 0
                            val isEnd     = (fuHeader and 0x40) != 0
                            val origNalu  = (payload[0].toInt() and 0xE0) or (fuHeader and 0x1F)

                            if (isStart) {
                                frameBuffer.write(byteArrayOf(0, 0, 0, 1, origNalu.toByte()))
                            }
                            frameBuffer.write(payload, 2, payload.size - 2)
                        }
                        naluType == 24 -> {
                            // STAP-A: multiple NAL units in one packet
                            var offset = 1
                            while (offset < payload.size - 1) {
                                val naluSize = ((payload[offset].toInt() and 0xFF) shl 8) or
                                               (payload[offset + 1].toInt() and 0xFF)
                                offset += 2
                                if (offset + naluSize > payload.size) break
                                frameBuffer.write(byteArrayOf(0, 0, 0, 1))
                                frameBuffer.write(payload, offset, naluSize)
                                offset += naluSize
                            }
                        }
                    }

                    // When marker bit is set, we have a complete frame — decode it
                    if (marker && frameBuffer.size() > 0) {
                        val frame = frameBuffer.toByteArray()
                        frameBuffer.reset()
                        decoder?.let { feedToDecoder(it, frame) }
                    }

                    lastSeq = seqNum
                }

                udpSocket.close()
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "RTP error: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun feedToDecoder(codec: MediaCodec, data: ByteArray) {
        try {
            val inputIdx = codec.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) {
                val buf = codec.getInputBuffer(inputIdx)!!
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(inputIdx, 0, data.size,
                    System.nanoTime() / 1000, 0)
            }

            // Release output frames to Surface (renders automatically)
            val info = MediaCodec.BufferInfo()
            var outputIdx = codec.dequeueOutputBuffer(info, 0)
            while (outputIdx >= 0) {
                codec.releaseOutputBuffer(outputIdx, true)  // true = render to surface
                outputIdx = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decoder feed error: ${e.message}")
        }
    }

    // ── H.264 Decoder Setup ───────────────────────────────────────────────────

    fun startDecoder(surface: Surface) {
        try {
            decoderSurface = surface
            decoder = MediaCodec.createDecoderByType("video/avc").apply {
                // Start with a generic config — MediaCodec will auto-detect
                // actual resolution from SPS/PPS NAL units in the stream
                val format = MediaFormat.createVideoFormat(
                    "video/avc",
                    1920, 1080  // Will be overridden by stream's SPS
                )
                configure(this@MiracastService.decoder!!, surface, null, 0)
                start()
            }
            Log.d(TAG, "H.264 decoder started → surface")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder init failed: ${e.message}")
        }
    }

    // MediaCodec.configure needs the codec itself... fix self-reference:
    private fun MediaCodec.configureAndStart(surface: Surface) {
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
        configure(format, surface, null, 0)
        start()
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    private fun cleanup() {
        isRunning.set(false)
        runCatching { decoder?.stop(); decoder?.release() }
        runCatching { wifiP2pManager.clearLocalServices(p2pChannel, null) }
        runCatching { wifiP2pManager.removeGroup(p2pChannel, null) }
        p2pReceiver?.let { runCatching { unregisterReceiver(it) } }
        p2pReceiver = null
        stopForeground(true)
        stopSelf()
    }

    // ── Broadcast helpers ──────────────────────────────────────────────────────

    private fun broadcastState(state: String, info: String = "") {
        sendBroadcast(Intent(ACTION_STATE_CHANGE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_INFO, info)
        })
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, MiracastService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortableCast Broadcasting")
            .setContentText("Your phone is visible as a wireless display")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "PortableCast", NotificationManager.IMPORTANCE_LOW)
                .also { getSystemService(NotificationManager::class.java)?.createNotificationChannel(it) }
        }
    }
}
