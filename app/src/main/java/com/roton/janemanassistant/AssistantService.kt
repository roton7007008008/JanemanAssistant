package com.roton.janemanassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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

    private val GEMINI_API_KEY = "AQ.Ab8RN6LwpcqBwrQg-FX2RQbRVR_1lZFo8rWYwjzTfFxbgknvlQ"

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingBubbleView: View? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification())
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        // স্ক্রিনে ভাসমান বাবল চালু
        showFloatingBubble()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            speak("আমি Janeman Assistant বলছি। আমি রেডি আছি বস!")
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
        }
    }

    // হোমস্ক্রিনের ভাসমান বাবল
    private fun showFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (floatingBubbleView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            160, 160,
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
                            initialX = params.x
                            initialY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - touchX).toInt()
                            params.y = initialY + (event.rawY - touchY).toInt()
                            windowManager?.updateViewLayout(floatingBubbleView, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
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
        try { windowManager?.addView(floatingBubbleView, params) } catch (e: Exception) {}
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
        broadcastTelemetry("You: \"$command\"", "AI: চিন্তা করছি...")

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
            try { startActivity(intent) } catch (e: Exception) {}
            return
        }

        // ২. মেসেঞ্জারে মেসেজ টাইপ ও পাঠানো (প্রথমে মেসেঞ্জার ওপেন করে তারপর টাইপ করবে)
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার") || lower.contains("মেসেজ")) {
            val msg = command.replace("মেসেঞ্জার", "").replace("মেসেজ", "").replace("পাঠাও", "").replace("লেখো", "").trim()
            speak("মেসেঞ্জারে মেসেজ টাইপ করছি")
            
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage("com.facebook.orca")
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                
                // ১.২ সেকেন্ড পর লেখাটি টাইপ ও সেন্ড করবে
                mainHandler.postDelayed({
                    MyAccessibilityService.instance?.typeAndSendMessage(msg)
                }, 1200)
            }
            return
        }

        // ৩. কল দেওয়া (১০০% ডিরেক্ট কল ফিক্স)
        if (lower.contains("call") || lower.contains("কল")) {
            val name = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "", ignoreCase = true).replace("কে", "").replace("রে", "").trim()
            
            speak("$name কে কল দিচ্ছি")
            directCallNumber(name)
            return
        }

        // ৪. টিকটক স্ক্রল
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ") || lower.contains("পাল্টাও")) {
            speak("স্ক্রল করছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৫. যেকোনো অ্যাপ ওপেন
        val appKeywords = listOf("খোলো", "খোল", "ওপেন", "চালাও", "যাও", "ঢোকো", "open")
        for (k in appKeywords) {
            if (lower.contains(k)) {
                val cleanApp = command.replace(k, "", ignoreCase = true).replace("অ্যাপে", "").replace("এ", "").trim()
                if (launchAppDirect(cleanApp)) {
                    speak("$cleanApp খুলে দিয়েছি")
                    return
                }
            }
        }

        // ৬. সাধারণ প্রশ্নের উত্তর (Gemini AI)
        callGeminiFallback(command)
    }

    // সরাসরি ডায়াল করে কল দেওয়া
    private fun directCallNumber(target: String) {
        val number = getPhoneNumberSmart(target)
        if (number != null) {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(callIntent)
            } catch (e: Exception) {
                // কোনো সিকিউরিটি আটকালে ডায়ালপ্যাডে সরাসরি নম্বর বসিয়ে দেবে
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(dialIntent)
            }
        } else {
            speak("স্যার, কন্টাক্টে $target পাওয়া যায়নি")
        }
    }

    private fun getPhoneNumberSmart(name: String): String? {
        val cleanName = name.lowercase().trim()
        val englishSound = cleanName.replace("মিম", "mim").replace("রতন", "roton")
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return null

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val cName = it.getString(nameIdx)?.lowercase() ?: ""
                val cNum = it.getString(numIdx) ?: ""
                if (cName.contains(cleanName) || cName.contains(englishSound)) {
                    return cNum
                }
            }
        }
        return null
    }

    private fun launchAppDirect(appName: String): Boolean {
        val lower = appName.lowercase()
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(lower) || app.packageName.lowercase().contains(lower)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    return true
                }
            }
        }
        return false
    }

    private fun callGeminiFallback(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val res = model.generateContent("Give a 1 short sentence answer in Bengali to: $command").text ?: ""
                speak(res)
                broadcastTelemetry("You: $command", "Jarvis: $res")
            } catch (e: Exception) {}
        }
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
            .setContentText("Listening and ready on screen...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        if (floatingBubbleView != null) {
            windowManager?.removeView(floatingBubbleView)
            floatingBubbleView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
