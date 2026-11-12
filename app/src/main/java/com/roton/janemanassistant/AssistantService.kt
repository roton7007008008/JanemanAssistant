package com.roton.janemanassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
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
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class AssistantService : Service(), TextToSpeech.OnInitListener {

    private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY_HERE"

    // AI BRAIN
    data class AppMemory(val label: String, val packageName: String, val phoneticKey: String)
    private val aiAppBrain = mutableListOf<AppMemory>()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // হোমস্ক্রিন ভাসমান বাবল ভেরিয়েবল
    private var windowManager: WindowManager? = null
    private var floatingBubbleView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification())
        tts = TextToSpeech(this, this)

        scanAndBuildAppBrain()
        initSpeechRecognizer()

        // হোমস্ক্রিনে মেসেঞ্জারের মতো ভাসমান বাবল তৈরি
        showFloatingBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            speak("আমি Janeman Assistant বলছি। আপনার হোমস্ক্রিনে বাবল চালু হয়ে গেছে বস!")
        }
    }

    // মেসেঞ্জার চ্যাট হেডের মতো ভাসমান বাবল তৈরি ও ড্র্যাগ কন্ট্রোল
    private fun showFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (floatingBubbleView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            150, 150, // বাবলের সাইজ
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 250
        }

        val bubble = ImageView(this).apply {
            try {
                setImageResource(R.drawable.app_icon)
            } catch (e: Exception) {
                setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            setBackgroundColor(Color.TRANSPARENT)

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
                            windowManager?.updateViewLayout(floatingBubbleView, layoutParams)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            // বাবলটিতে টাচ করলে সাথে সাথে কথা শুনবে
                            if (Math.abs(event.rawX - touchX) < 15 && Math.abs(event.rawY - touchY) < 15) {
                                speak("শুনছি বস, বলুন!")
                                startListening()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        floatingBubbleView = bubble
        try {
            windowManager?.addView(floatingBubbleView, layoutParams)
        } catch (e: Exception) {}
    }

    private fun scanAndBuildAppBrain() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                aiAppBrain.clear()
                val pm = packageManager
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

                for (info in apps) {
                    val appLabel = info.loadLabel(pm).toString()
                    val pkgName = info.activityInfo.packageName
                    val phonetic = appLabel.lowercase().replace(" ", "").replace("-", "")

                    aiAppBrain.add(AppMemory(appLabel, pkgName, phonetic))
                }
            } catch (e: Exception) {}
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    private fun broadcastTelemetry(userSaid: String, aiAction: String) {
        val intent = Intent("JARVIS_TELEMETRY").apply {
            putExtra("userSaid", userSaid)
            putExtra("aiAction", aiAction)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
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
                        handleSmartCommand(command)
                    }
                    if (isListening) {
                        mainHandler.postDelayed({ startListening() }, 1200)
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

    private fun handleSmartCommand(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You: \"$command\"", "AI: নির্দেশ পালন করছি...")

        // ১. ইউটিউব গান
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান") || lower.contains("song")) {
            val query = command.replace("youtube", "", ignoreCase = true)
                .replace("ইউটিউব", "").replace("ওপেন করো", "").replace("গান ছাড়ো", "").replace("গান চালাও", "").trim()
            val finalQuery = if (query.isEmpty()) "Ashes band songs" else query
            speak("ইউটিউবে $finalQuery ছাড়া হচ্ছে")
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

        // ২. টিকটক স্ক্রল
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ") || lower.contains("পাল্টাও")) {
            speak("স্ক্রল করছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৩. কল দেওয়া
        if (lower.contains("call") || lower.contains("কল")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val name = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "", ignoreCase = true).replace("sim 2", "").replace("sim 1", "").replace("কে", "").trim()
            speak("$name কে কল দিচ্ছি")
            makeCall(name, sim)
            return
        }

        // ৪. AI BRAIN থেকে যেকোনো অ্যাপ ওপেন
        if (lower.contains("খোলো") || lower.contains("খোল") || lower.contains("ওপেন") || 
            lower.contains("চালাও") || lower.contains("যাও") || lower.contains("ঢোকো") || lower.contains("open")) {
            
            val appQuery = command.replace("খোলো", "").replace("খোল", "").replace("ওপেন করো", "")
                .replace("চালাও", "").replace("যাও", "").replace("ঢোকো", "").replace("open", "", ignoreCase = true)
                .replace("অ্যাপে", "").replace("অ্যাপসে", "").replace("এ", "").replace("তে", "").trim()

            val opened = openFromAiBrain(appQuery)
            if (opened != null) {
                speak("$opened খুলে দিয়েছি")
                return
            }
        }

        // সাধারণ প্রশ্ন হলে Gemini AI
        callGeminiFallback(command)
    }

    private fun openFromAiBrain(query: String): String? {
        val cleanQuery = query.lowercase().trim()
        val englishKey = convertBanglaToEnglishSound(cleanQuery)

        for (app in aiAppBrain) {
            val labelLower = app.label.lowercase()
            val pkgLower = app.packageName.lowercase()

            if (labelLower.contains(cleanQuery) || labelLower.contains(englishKey) ||
                pkgLower.contains(cleanQuery) || pkgLower.contains(englishKey) ||
                app.phoneticKey.contains(englishKey)) {

                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(launchIntent)
                    return app.label
                }
            }
        }
        return null
    }

    private fun convertBanglaToEnglishSound(bangla: String): String {
        return bangla.replace("ক্যালকুলেটর", "calculator")
            .replace("ক্যামেরা", "camera")
            .replace("গ্যালারি", "gallery")
            .replace("ছবি", "photos")
            .replace("সেটিংস", "settings")
            .replace("ক্রোম", "chrome")
            .replace("প্লে স্টোর", "vending")
            .replace("মেসেজ", "message")
            .replace("ঘড়ি", "clock")
            .replace("ফাইল", "file")
            .replace("বিকাশ", "bkash")
            .replace("নগদ", "nagad")
            .replace("ফ্রি ফায়ার", "free fire")
            .replace("টিকটক", "tiktok")
            .replace("ফেসবুক", "facebook")
            .replace("হোয়াটসঅ্যাপ", "whatsapp")
            .replace("মেসেঞ্জার", "messenger")
    }

    private fun callGeminiFallback(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val res = model.generateContent("Give a 1 short sentence answer in Bengali to: $command").text ?: ""
                speak(res)
                broadcastTelemetry("You: $command", "Jarvis: $res")
            } catch (e: Exception) {
                speak("আমি আপনার নির্দেশটি বুঝতে পেরেছি বস!")
            }
        }
    }

    private fun makeCall(contactName: String, simSlot: Int) {
        val number = getPhoneNumber(contactName) ?: return
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
            val channel = NotificationChannel("assistant_channel", "Janeman Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "assistant_channel")
            .setContentTitle("JANEMAN ACTIVE")
            .setContentText("Floating bubble active on screen...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()

        // বাবল রিমুভ করা
        if (floatingBubbleView != null) {
            windowManager?.removeView(floatingBubbleView)
            floatingBubbleView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
