package com.roton.janemanassistant

import android.Manifest
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var tvStatus: TextView
    private lateinit var tvReactorText: TextView
    private lateinit var tvLiveSpeech: TextView
    private lateinit var tvAiResponse: TextView
    private lateinit var btnToggleAssistant: Button
    private lateinit var btnEnableAccessibility: Button

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val userSaid = intent?.getStringExtra("userSaid") ?: ""
            val aiAction = intent?.getStringExtra("aiAction") ?: ""
            tvLiveSpeech.text = userSaid
            tvAiResponse.text = aiAction
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
        tvReactorText = findViewById(R.id.tvReactorText)
        tvLiveSpeech = findViewById(R.id.tvLiveSpeech)
        tvAiResponse = findViewById(R.id.tvAiResponse)
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
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Turn ON 'Display over other apps' to allow opening YouTube/Call", Toast.LENGTH_LONG).show()
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
        try {
            unregisterReceiver(telemetryReceiver)
        } catch (e: Exception) {}
    }

    private fun startAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        tvStatus.text = "● JARVIS ONLINE & LISTENING"
        tvStatus.setTextColor(Color.parseColor("#00E5FF"))
        tvReactorText.text = "ARC CORE\nACTIVE"
        tvReactorText.setTextColor(Color.parseColor("#76FF03"))
        btnToggleAssistant.text = "DISENGAGE SYSTEM"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#FF1744"))
    }

    private fun stopAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        tvStatus.text = "● SYSTEM OFFLINE"
        tvStatus.setTextColor(Color.parseColor("#FF1744"))
        tvReactorText.text = "ARC CORE\nSTANDBY"
        tvReactorText.setTextColor(Color.parseColor("#00E5FF"))
        btnToggleAssistant.text = "INITIALIZE SYSTEM"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#00E5FF"))
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
