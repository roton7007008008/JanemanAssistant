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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var tvHudStatus: TextView
    private lateinit var tvLiveLog: TextView
    private lateinit var tvAppsTitle: TextView
    private lateinit var lvInstalledApps: ListView
    private lateinit var btnToggleAssistant: Button
    private lateinit var btnToggleFloatingBall: Button
    private lateinit var btnEnableAccessibility: Button

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: ""
            tvLiveLog.text = log
        }
    }

    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHudStatus = findViewById(R.id.tvHudStatus)
        tvLiveLog = findViewById(R.id.tvLiveLog)
        tvAppsTitle = findViewById(R.id.tvAppsTitle)
        lvInstalledApps = findViewById(R.id.lvInstalledApps)
        btnToggleAssistant = findViewById(R.id.btnToggleAssistant)
        btnToggleFloatingBall = findViewById(R.id.btnToggleFloatingBall)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        requestAppPermissions()
        checkOverlayPermission()

        // ফোন ইনস্টল হওয়া সব অ্যাপ স্ক্যান করা
        scanInstalledApps()

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

        btnToggleFloatingBall.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                checkOverlayPermission()
                return@setOnClickListener
            }
            val intent = Intent(this, AssistantService::class.java).apply {
                putExtra("TOGGLE_FLOATING", true)
            }
            startService(intent)
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    // ফোনের সব অ্যাপ স্ক্যান করে স্ক্রিনে দেখানো
    private fun scanInstalledApps() {
        try {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appNames = ArrayList<String>()

            for (app in packages) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    val label = pm.getApplicationLabel(app).toString()
                    appNames.add("▶  $label")
                }
            }

            appNames.sort()
            tvAppsTitle.text = "[ SCANNED APPS: ${appNames.size} AVAILABLE ]"

            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, appNames)
            lvInstalledApps.adapter = adapter
        } catch (e: Exception) {}
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
        try {
            unregisterReceiver(telemetryReceiver)
        } catch (e: Exception) {}
    }

    private fun startAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isServiceRunning = true
        tvHudStatus.text = "● JANEMAN ASSISTANT ONLINE"
        tvHudStatus.setTextColor(Color.parseColor("#00F2FE"))
        btnToggleAssistant.text = "STOP ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#FF3366"))
    }

    private fun stopAssistant() {
        val serviceIntent = Intent(this, AssistantService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        tvHudStatus.text = "● SYSTEM STANDBY"
        tvHudStatus.setTextColor(Color.parseColor("#FF3366"))
        btnToggleAssistant.text = "START ASSISTANT"
        btnToggleAssistant.setBackgroundColor(Color.parseColor("#00F2FE"))
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
