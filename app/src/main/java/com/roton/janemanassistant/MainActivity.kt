package com.roton.janemanassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
    private lateinit var btnToggleAssistant: Button
    private lateinit var btnEnableAccessibility: Button

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
        btnToggleAssistant = findViewById(R.id.btnToggleAssistant)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        // প্রয়োজনীয় সব পারমিশন চাওয়া
        requestAppPermissions()

        // অ্যাসিস্ট্যান্ট অন/অফ সুইচ
        btnToggleAssistant.setOnClickListener {
            if (!hasPermissions()) {
                Toast.makeText(this, "Please grant all permissions first!", Toast.LENGTH_SHORT).show()
                requestAppPermissions()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopAssistant()
            } else {
                startAssistant()
            }
        }

        // মেসেঞ্জারে টাইপ ও সোয়াইপ পারমিশন অন করার বাটন
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Turn ON 'Janeman Assistant' from downloaded apps", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        tvStatus.text = "Status: Assistant is Running (Listening...)"
        tvStatus.setTextColor(Color.parseColor("#00E676"))
        btnToggleAssistant.text = "STOP ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#FF5252"))
    }

    private fun stopAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        tvStatus.text = "Status: Assistant is OFF"
        tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
        btnToggleAssistant.text = "START ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#00E676"))
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
