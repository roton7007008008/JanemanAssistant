package com.roton.janemanassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveLog: TextView
    private lateinit var tvOrbState: TextView
    private lateinit var geminiOrbGlow: View
    private lateinit var geminiOrbCore: View
    private lateinit var btnToggleAssistant: Button
    private lateinit var btnEnableAccessibility: Button

    private var orbAnimator: ObjectAnimator? = null

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val userSaid = intent?.getStringExtra("userSaid") ?: ""
            val aiAction = intent?.getStringExtra("aiAction") ?: ""
            tvLiveLog.text = "$userSaid\n$aiAction"
        }
    }

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLiveLog = findViewById(R.id.tvLiveLog)
        tvOrbState = findViewById(R.id.tvOrbState)
        geminiOrbGlow = findViewById(R.id.geminiOrbGlow)
        geminiOrbCore = findViewById(R.id.geminiOrbCore)
        btnToggleAssistant = findViewById(R.id.btnToggleAssistant)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        requestAppPermissions()
        checkOverlayPermission()

        btnToggleAssistant.setOnClickListener {
            if (!hasPermissions()) {
                Toast.makeText(this, "Permissions required!", Toast.LENGTH_SHORT).show()
                requestAppPermissions()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopAssistant()
            } else {
                startAssistant()
            }
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("JARVIS_TELEMETRY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(telemetryReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(telemetryReceiver) } catch (e: Exception) {}
    }

    private fun startAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true

        tvStatus.text = "● ASSISTANT ONLINE (Listening...)"
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        tvOrbState.text = "LISTENING"
        btnToggleAssistant.text = "STOP ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#FF1744"))

        // Gemini Orb স্পন্দিত অ্যানিমেশন শুরু
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
        orbAnimator = ObjectAnimator.ofPropertyValuesHolder(geminiOrbGlow, scaleX, scaleY).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false

        tvStatus.text = "● SYSTEM OFFLINE"
        tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
        tvOrbState.text = "STANDBY"
        btnToggleAssistant.text = "START ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#00F2FE"))

        // অ্যানিমেশন বন্ধ
        orbAnimator?.cancel()
        geminiOrbGlow.scaleX = 1.0f
        geminiOrbGlow.scaleY = 1.0f
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAppPermissions() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 100)
        }
    }
}
