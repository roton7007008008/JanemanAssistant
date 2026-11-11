package com.roton.janemanassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class AssistantService : Service(), TextToSpeech.OnInitListener {

    private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY_HERE"

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isTorchOn = false
    private var windowManager: WindowManager? = null
    private var floatingOrbView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification())
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            speak("ইয়েস স্যার! জারভিস সিস্টেম পুরোপুরি অনলাইন। বলুন আমি কীভাবে সাহায্য করতে পারি?")
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("TOGGLE_FLOATING", false) == true) {
            toggleFloatingOrb()
        } else {
            startListening()
        }
        return START_STICKY
    }

    private fun broadcastTelemetry(logText: String) {
        val intent = Intent("JARVIS_TELEMETRY").apply {
            putExtra("log", logText)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    broadcastTelemetry("Jarvis: শুনছি স্যার... বলুন।")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (isListening) {
                        mainHandler.postDelayed({ startListening() }, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0]
                        handleCommand(command)
                    }
                    if (isListening) {
                        mainHandler.postDelayed({ startListening() }, 1500)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }
        try { speechRecognizer?.startListening(intent) } catch (e: Exception) {}
    }

    private fun handleCommand(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You Said: \"$command\"")

        // ১. ফ্ল্যাশলাইট / টর্চ অন-অফ
        if (lower.contains("torch") || lower.contains("টর্চ") || lower.contains("লাইট") || lower.contains("flash")) {
            if (lower.contains("বন্ধ") || lower.contains("off")) {
                toggleTorch(false)
                speak("স্যার, টর্চ বন্ধ করে দিয়েছি")
                broadcastTelemetry("Jarvis: টর্চ বন্ধ করা হয়েছে।")
            } else {
                toggleTorch(true)
                speak("স্যার, টর্চ জ্বালিয়ে দিয়েছি")
                broadcastTelemetry("Jarvis: টর্চ জ্বালানো হয়েছে।")
            }
            return
        }

        // ২. ইউটিউব
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান") || lower.contains("song")) {
            val query = command.replace("youtube", "", ignoreCase = true)
                .replace("ইউটিউব", "").replace("ওপেন করো", "").replace("গান ছাড়ো", "").replace("গান চালাও", "").trim()
            val finalQuery = if (query.isEmpty()) "Ashes band songs" else query
            speak("স্যার, ইউটিউবে $finalQuery ছাড়া হচ্ছে")
            broadcastTelemetry("Jarvis: Playing YouTube -> $finalQuery")

            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", finalQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try { startActivity(intent) } catch (e: Exception) {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$finalQuery")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(web)
            }
            return
        }

        // ৩. টিকটক ওপেন ও স্ক্রল
        if (lower.contains("tiktok") || lower.contains("টিকটক")) {
            if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ")) {
                speak("স্যার, স্ক্রল করছি")
                MyAccessibilityService.instance?.scrollDown()
            } else {
                speak("স্যার, টিকটক খুলে দিচ্ছি")
                openDirectApp("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            }
            return
        }

        // ৪. স্ক্রল
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ")) {
            speak("স্যার, স্ক্রল করছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৫. মেসেঞ্জার
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার") || lower.contains("মেসেজ")) {
            val msg = command.replace("মেসেঞ্জার", "").replace("মেসেজ", "").replace("পাঠাও", "").replace("লেখো", "").trim()
            speak("স্যার, মেসেঞ্জারে টাইপ করছি")
            MyAccessibilityService.instance?.typeAndSendMessage(msg)
            return
        }

        // ৬. কল দেওয়া
        if (lower.contains("call") || lower.contains("কল")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val rawName = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "").replace("sim 2", "").replace("sim 1", "").trim()
            val cleanName = rawName.removeSuffix("কে").removeSuffix("রে").trim()
            speak("স্যার, $cleanName কে কল দিচ্ছি")
            makeCall(cleanName, sim)
            return
        }

        // ৭. অ্যাপ ওপেন
        if (lower.contains("open") || lower.contains("ওপেন") || lower.contains("খোলো") || lower.contains("যাও")) {
            val appName = command.replace("open", "", ignoreCase = true)
                .replace("ওপেন করো", "").replace("খোলো", "").replace("যাও", "").trim()
            if (openUniversalApp(appName)) {
                speak("স্যার, $appName খুলে দিয়েছি")
                return
            }
        }

        // ৮. সাধারণ সব কথার বুদ্ধিমান উত্তর (Gemini AI Chat)
        talkWithGeminiAi(command)
    }

    private fun toggleTorch(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
        } catch (e: Exception) {}
    }

    private fun openDirectApp(vararg packageNames: String): Boolean {
        val pm = packageManager
        for (pkg in packageNames) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun openUniversalApp(appName: String): Boolean {
        val lower = appName.lowercase()
        if (lower.contains("facebook") || lower.contains("ফেসবুক")) return openDirectApp("com.facebook.katana")
        if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) return openDirectApp("com.whatsapp")
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার")) return openDirectApp("com.facebook.orca")

        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(lower)) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(launchIntent)
                    return true
                }
            }
        }
        return false
    }

    private fun talkWithGeminiAi(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val prompt = "You are JARVIS, an extremely smart, polite, and loyal AI assistant created by Tony Stark. The user is your boss. Always address the user as 'Sir' (স্যার) in Bengali. Answer this question naturally, intelligently and directly in 1-2 Bengali sentences: $command"
                val res = model.generateContent(prompt).text ?: ""
                val cleanAnswer = res.trim()
                
                launch(Dispatchers.Main) {
                    speak(cleanAnswer)
                    broadcastTelemetry("Jarvis: $cleanAnswer")
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    speak("স্যার, আমি আপনার নির্দেশ বুঝতে পেরেছি। দয়া করে আরেকবার বলুন।")
                    broadcastTelemetry("Jarvis: নির্দেশটি আবার বলুন, স্যার।")
                }
            }
        }
    }

    // স্ক্রিনের উপর ভাসমান জারভিস বল (Floating Orb)
    private fun toggleFloatingOrb() {
        if (floatingOrbView != null) {
            windowManager?.removeView(floatingOrbView)
            floatingOrbView = null
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layoutParams = WindowManager.LayoutParams(
            160, 160,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val orb = TextView(this).apply {
            text = "JARVIS"
            textSize = 10f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var touchX = 0f
                private var touchY = 0f

                override fun onTouch(v: View?, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            touchX = event.rawX
                            touchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            layoutParams.x = initialX + (event.rawX - touchX).toInt()
                            layoutParams.y = initialY + (event.rawY - touchY).toInt()
                            windowManager?.updateViewLayout(floatingOrbView, layoutParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (Math.abs(event.rawX - touchX) < 10 && Math.abs(event.rawY - touchY) < 10) {
                                speak("ইয়েস স্যার, বলুন!")
                                startListening()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        floatingOrbView = orb
        try {
            windowManager?.addView(floatingOrbView, layoutParams)
        } catch (e: Exception) {}
    }

    private fun makeCall(contactName: String, simSlot: Int) {
        val number = getPhoneNumber(contactName)
        if (number == null) {
            speak("স্যার, কন্টাক্টে $contactName খুঁজে পাওয়া যায়নি")
            return
        }

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecomManager.callCapablePhoneAccounts
        val selectedAccount: PhoneAccountHandle? = if (simSlot == 2 && accounts.size > 1) accounts[1] else accounts.firstOrNull()

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            selectedAccount?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        try { startActivity(callIntent) } catch (e: Exception) {}
    }

    private fun getPhoneNumber(name: String): String? {
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        var number: String? = null
        cursor?.use { if (it.moveToFirst()) number = it.getString(0) }
        return number
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("assistant_channel", "JARVIS HUD", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "assistant_channel")
            .setContentTitle("JARVIS HUD ONLINE")
            .setContentText("Awaiting voice command, Sir...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        if (floatingOrbView != null) {
            windowManager?.removeView(floatingOrbView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
