package com.phonecast.miracast

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * MainActivity
 * ============
 * - Asks for location permission (required for Wi-Fi Direct on Android 10+)
 * - Starts MiracastService which advertises the phone as a wireless display
 * - Shows status: Waiting → PC Found → Connecting → LIVE
 * - Once connected, shows the PC's mirrored screen via a SurfaceView
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus   : TextView
    private lateinit var tvSubtitle : TextView
    private lateinit var tvDevName  : TextView
    private lateinit var btnToggle  : Button
    private lateinit var surfaceView: SurfaceView
    private lateinit var cardInfo   : View
    private lateinit var ivSignal   : TextView

    private var isRunning = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                MiracastService.ACTION_STATE_CHANGE -> {
                    val state = intent.getStringExtra(MiracastService.EXTRA_STATE) ?: return
                    val extra = intent.getStringExtra(MiracastService.EXTRA_INFO) ?: ""
                    updateUI(state, extra)
                }
                MiracastService.ACTION_SURFACE_NEEDED -> {
                    // Service needs our SurfaceView to render the PC stream
                    val sIntent = Intent(this@MainActivity, MiracastService::class.java).apply {
                        action = MiracastService.ACTION_PROVIDE_SURFACE
                    }
                    MiracastService.surfaceHolder = surfaceView.holder
                    startService(sIntent)
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStatus    = findViewById(R.id.tvStatus)
        tvSubtitle  = findViewById(R.id.tvSubtitle)
        tvDevName   = findViewById(R.id.tvDevName)
        btnToggle   = findViewById(R.id.btnToggle)
        surfaceView = findViewById(R.id.surfaceView)
        cardInfo    = findViewById(R.id.cardInfo)
        ivSignal    = findViewById(R.id.ivSignal)

        // Set the display name Windows will see in Win+K
        val deviceName = Build.MODEL  // e.g. "Samsung Galaxy A26"
        tvDevName.text = "Broadcasting as: \"$deviceName\""

        btnToggle.setOnClickListener {
            if (!isRunning) requestPermissionsAndStart() else stopCasting()
        }

        // Hide surface until a PC connects
        surfaceView.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MiracastService.ACTION_STATE_CHANGE)
            addAction(MiracastService.ACTION_SURFACE_NEEDED)
        }
        registerReceiver(stateReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            startCasting()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCasting()
        } else {
            tvSubtitle.text = "Location permission is required for Wi-Fi Direct"
        }
    }

    // ── Start / Stop ───────────────────────────────────────────────────────────

    private fun startCasting() {
        isRunning = true
        btnToggle.text = "⏹  Stop Broadcasting"
        btnToggle.setBackgroundColor(0xFFE53935.toInt())

        val intent = Intent(this, MiracastService::class.java).apply {
            action = MiracastService.ACTION_START
        }
        startForegroundService(intent)

        updateUI("ADVERTISING", "")
    }

    private fun stopCasting() {
        isRunning = false
        btnToggle.text = "📡  Start Broadcasting"
        btnToggle.setBackgroundColor(0xFF1565C0.toInt())

        val intent = Intent(this, MiracastService::class.java).apply {
            action = MiracastService.ACTION_STOP
        }
        startService(intent)

        surfaceView.visibility = View.GONE
        cardInfo.visibility    = View.VISIBLE
        updateUI("IDLE", "")
    }

    // ── UI Updates ─────────────────────────────────────────────────────────────

    private fun updateUI(state: String, extra: String) {
        when (state) {
            "IDLE" -> {
                tvStatus.text   = "Ready"
                tvSubtitle.text = "Tap the button to start broadcasting"
                ivSignal.text   = "📵"
            }
            "ADVERTISING" -> {
                tvStatus.text   = "Broadcasting..."
                tvSubtitle.text = "On your PC: press Win + K\nand look for your phone's name"
                ivSignal.text   = "📡"
                animatePulse()
            }
            "PC_FOUND" -> {
                tvStatus.text   = "PC Found! ✓"
                tvSubtitle.text = "Connecting to: $extra"
                ivSignal.text   = "🔗"
            }
            "CONNECTING" -> {
                tvStatus.text   = "Connecting..."
                tvSubtitle.text = "Setting up the display stream"
                ivSignal.text   = "⚡"
            }
            "LIVE" -> {
                tvStatus.text   = "🔴 LIVE"
                tvSubtitle.text = "Mirroring PC screen — $extra"
                ivSignal.text   = "🖥"
                // Show the video surface, hide info card
                surfaceView.visibility = View.VISIBLE
                cardInfo.visibility    = View.GONE
            }
            "ERROR" -> {
                tvStatus.text   = "❌ Error"
                tvSubtitle.text = extra
                ivSignal.text   = "⚠️"
            }
        }
    }

    private fun animatePulse() {
        if (!isRunning) return
        ivSignal.animate().alpha(0.3f).setDuration(600).withEndAction {
            ivSignal.animate().alpha(1f).setDuration(600).withEndAction {
                animatePulse()
            }.start()
        }.start()
    }
}
