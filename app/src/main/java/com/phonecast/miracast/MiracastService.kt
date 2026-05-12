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

class MiracastService : Service() {

    companion object {
        private const val TAG = "MiracastService"
        const val ACTION_STATE_CHANGE   = "com.phonecast.STATE_CHANGE"
        const val ACTION_SURFACE_NEEDED = "com.phonecast.SURFACE_NEEDED"
        const val EXTRA_STATE           = "state"
        const val EXTRA_INFO            = "info"
        const val ACTION_START          = "ACTION_START"
        const val ACTION_STOP           = "ACTION_STOP"
        const val ACTION_PROVIDE_SURFACE = "ACTION_PROVIDE_SURFACE"
        const val RTSP_PORT             = 7236
        const val RTP_PORT              = 16384
        private const val CHANNEL_ID    = "portablecast_channel"
        private const val NOTIF_ID      = 202

        @Volatile var surfaceHolder: SurfaceHolder? = null
    }

    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var p2pChannel: Channel
    private var p2pReceiver: BroadcastReceiver? = null
    private val isRunning = AtomicBoolean(false)
    private var rtspThread: Thread? = null
    private var rtpThread: Thread? = null
    private var decoder: MediaCodec? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        p2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                isRunning.set(true)
                setupWifiDirect()
            }
            ACTION_STOP -> cleanup()
            ACTION_PROVIDE_SURFACE -> {
                surfaceHolder?.surface?.let { startDecoder(it) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun setupWifiDirect() {
        val record = mapOf(
            "devicetype" to "1",
            "av_change_notice" to "0",
            "wfd_version" to "1.1"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            Build.MODEL, "_wfd._tcp", record
        )
        wifiP2pManager.clearLocalServices(p2pChannel, object : ActionListener {
            override fun onSuccess() { addService(serviceInfo) }
            override fun onFailure(r: Int) { addService(serviceInfo) }
        })
    }

    private fun addService(serviceInfo: WifiP2pDnsSdServiceInfo) {
        wifiP2pManager.addLocalService(p2pChannel, serviceInfo, object : ActionListener {
            override fun onSuccess() {
                broadcastState("ADVERTISING")
                registerP2pReceiver()
                startRtspServer()
            }
            override fun onFailure(reason: Int) {
                broadcastState("ERROR", "Wi-Fi Direct failed (code $reason)")
            }
        })
    }

    private fun registerP2pReceiver() {
        val filter = IntentFilter().apply {
            addAction(WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        p2pReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_NETWORK_INFO)
                    }
                    if (info?.isConnected == true) {
                        wifiP2pManager.requestConnectionInfo(p2pChannel) { connInfo ->
                            if (connInfo.groupFormed) {
                                val pcIp = connInfo.groupOwnerAddress?.hostAddress ?: "unknown"
                                broadcastState("PC_FOUND", pcIp)
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(p2pReceiver, filter)
    }

    private fun startRtspServer() {
        rtspThread = Thread {
            try {
                val server = ServerSocket(RTSP_PORT)
                while (isRunning.get()) {
                    val client = server.accept()
                    broadcastState("CONNECTING")
                    Thread { handleRtspClient(client) }.apply { isDaemon = true; start() }
                }
                server.close()
            } catch (e: Exception) {
                if (isRunning.get()) broadcastState("ERROR", "RTSP failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun handleRtspClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            var cseq = 0
            val sessionId = "12345678"

            while (isRunning.get() && !socket.isClosed) {
                val lines = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.isBlank()) break
                    lines.add(line!!)
                }
                if (lines.isEmpty()) break

                val method = lines[0].substringBefore(" ")
                cseq = lines.firstOrNull { it.startsWith("CSeq:", true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: cseq

                when (method) {
                    "OPTIONS" -> {
                        writer.print(rtspResponse(200, "OK", cseq,
                            "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN\r\n"))
                        writer.flush()
                    }
                    "GET_PARAMETER" -> {
                        val body = "wfd_video_formats: 00 00 02 02 0001DEFE 153 00000000 00000000 00 0000 0000 00 none none\r\nwfd_audio_codecs: AAC 00000001 00\r\nwfd_client_rtp_ports: RTP/AVP/UDP;unicast $RTP_PORT 0 mode=play\r\n"
                        writer.print(rtspResponse(200, "OK", cseq,
                            "Content-Type: text/parameters\r\nContent-Length: ${body.length}\r\n", body))
                        writer.flush()
                    }
                    "SET_PARAMETER" -> {
                        val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
                            ?.substringAfter(":")?.trim()?.toInt() ?: 0
                        if (len > 0) { val b = CharArray(len); reader.read(b, 0, len) }
                        writer.print(rtspResponse(200, "OK", cseq, ""))
                        writer.flush()
                    }
                    "SETUP" -> {
                        writer.print(rtspResponse(200, "OK", cseq,
                            "Transport: RTP/AVP/UDP;unicast;client_port=$RTP_PORT\r\nSession: $sessionId;timeout=60\r\n"))
                        writer.flush()
                        startRtpReceiver()
                    }
                    "PLAY" -> {
                        writer.print(rtspResponse(200, "OK", cseq,
                            "Session: $sessionId\r\nRange: npt=0-\r\n"))
                        writer.flush()
                        broadcastState("LIVE", socket.inetAddress.hostAddress ?: "")
                        sendBroadcast(Intent(ACTION_SURFACE_NEEDED))
                    }
                    "TEARDOWN" -> {
                        writer.print(rtspResponse(200, "OK", cseq, ""))
                        writer.flush()
                        break
                    }
                    else -> {
                        writer.print(rtspResponse(501, "Not Implemented", cseq, ""))
                        writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP error: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun rtspResponse(code: Int, reason: String, cseq: Int, headers: String, body: String = "") =
        "RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\n$headers\r\n$body"

    private fun startRtpReceiver() {
        rtpThread = Thread {
            try {
                val udpSocket = DatagramSocket(RTP_PORT)
                val buffer = ByteArray(65535)
                val packet = DatagramPacket(buffer, buffer.size)
                val frameBuffer = ByteArrayOutputStream()

                while (isRunning.get()) {
                    udpSocket.receive(packet)
                    if (packet.length < 12) continue

                    val data = packet.data
                    val marker = (data[1].toInt() and 0x80) != 0
                    val payload = data.copyOfRange(12, packet.length)
                    val naluType = payload[0].toInt() and 0x1F

                    when {
                        naluType in 1..23 -> {
                            frameBuffer.write(byteArrayOf(0, 0, 0, 1))
                            frameBuffer.write(payload)
                        }
                        naluType == 28 -> {
                            val fuHeader = payload[1].toInt()
                            val isStart = (fuHeader and 0x80) != 0
                            val origNalu = (payload[0].toInt() and 0xE0) or (fuHeader and 0x1F)
                            if (isStart) frameBuffer.write(byteArrayOf(0, 0, 0, 1, origNalu.toByte()))
                            frameBuffer.write(payload, 2, payload.size - 2)
                        }
                    }

                    if (marker && frameBuffer.size() > 0) {
                        val frame = frameBuffer.toByteArray()
                        frameBuffer.reset()
                        decoder?.let { feedToDecoder(it, frame) }
                    }
                }
                udpSocket.close()
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "RTP error: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun feedToDecoder(codec: MediaCodec, data: ByteArray) {
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var out = codec.dequeueOutputBuffer(info, 0)
            while (out >= 0) {
                codec.releaseOutputBuffer(out, true)
                out = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decoder error: ${e.message}")
        }
    }

    fun startDecoder(surface: Surface) {
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
            val codec = MediaCodec.createDecoderByType("video/avc")
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
        } catch (e: Exception) {
            Log.e(TAG, "Decoder init failed: ${e.message}")
        }
    }

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

    private fun broadcastState(state: String, info: String = "") {
        sendBroadcast(Intent(ACTION_STATE_CHANGE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_INFO, info)
        })
    }

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
