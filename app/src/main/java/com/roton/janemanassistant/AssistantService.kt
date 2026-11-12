package com.roton.janemanassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
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
            speak("আমি Janeman Assistant বলছি। আমি সম্পূর্ণ প্রস্তুত আছি!")
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
        broadcastTelemetry("You: \"$command\"", "AI: কাজ করছি...")

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

        // ৪. সরাসরি যেকোনো অ্যাপ ওপেন করার নিশ্চিত ইঞ্জিন (Direct Launcher)
        val appKeywords = listOf("খোলো", "খোল", "ওপেন", "চালাও", "যাও", "ঢোকো", "open")
        var isApp = false
        for (k in appKeywords) {
            if (lower.contains(k)) {
                isApp = true
                break
            }
        }

        if (isApp) {
            val cleanApp = command.replace("খোলো", "").replace("খোল", "").replace("ওপেন করো", "")
                .replace("চালাও", "").replace("যাও", "").replace("ঢোকো", "").replace("open", "", ignoreCase = true)
                .replace("অ্যাপে", "").replace("অ্যাপসে", "").replace("এ", "").replace("তে", "").trim()

            val success = launchAppGuaranteed(cleanApp)
            if (success) {
                speak("$cleanApp খুলে দিয়েছি")
            } else {
                speak("স্যার, আপনার ফোনে $cleanApp অ্যাপটি খুঁজে পাওয়া যায়নি")
            }
            return
        }

        // ৫. সাধারণ প্রশ্ন হলে তবেই শুধু Gemini AI ডাকবে
        callGeminiFallback(command)
    }

    // যেকোনো অ্যাপ গ্যারান্টি সহকারে ওপেন করার মেথড
    private fun launchAppGuaranteed(rawName: String): Boolean {
        val query = rawName.lowercase().trim()

        // ১. সিস্টেম অ্যাপস সরাসরি লঞ্চ
        try {
            if (query.contains("ক্যামেরা") || query.contains("camera")) {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                startActivity(intent)
                return true
            }
            if (query.contains("সেটিংস") || query.contains("settings")) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                startActivity(intent)
                return true
            }
            if (query.contains("গ্যালারি") || query.contains("ছবি") || query.contains("gallery") || query.contains("photos")) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {}

        // ২. প্যাকেজ ধরে সরাসরি ওপেন
        val directPackages = mapOf(
            "টিকটক" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "ফেসবুক" to listOf("com.facebook.katana", "com.facebook.lite"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "মেসেঞ্জার" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "হোয়াটসঅ্যাপ" to listOf("com.whatsapp"),
            "whatsapp" to listOf("com.whatsapp"),
            "বিকাশ" to listOf("com.bKash.customerapp"),
            "bkash" to listOf("com.bKash.customerapp"),
            "নগদ" to listOf("com.konasl.nagad"),
            "nagad" to listOf("com.konasl.nagad"),
            "ফ্রি ফায়ার" to listOf("com.dts.freefireth", "com.dts.freefiremax"),
            "ক্রোম" to listOf("com.android.chrome"),
            "chrome" to listOf("com.android.chrome"),
            "ক্যালকুলেটর" to listOf("com.google.android.calculator", "com.android.calculator2", "com.coloros.calculator"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.coloros.calculator")
        )

        for ((key, pkgs) in directPackages) {
            if (query.contains(key)) {
                for (pkg in pkgs) {
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        return true
                    }
                }
            }
        }

        // ৩. পুরো ফোনের সমস্ত অ্যাপ খুঁজে বের করা
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(query) || app.packageName.lowercase().contains(query)) {
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

    private fun callGeminiFallback(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val res = model.generateContent("Give a 1 short sentence answer in Bengali to: $command").text ?: ""
                speak(res)
                broadcastTelemetry("You: $command", "Jarvis: $res")
            } catch (e: Exception) {
                // কোনো ভুল হলে চুপ থাকবে, আর ওই এক কথা বারবার বলবে না
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
            .setContentTitle("JANEMAN ONLINE")
            .setContentText("Listening to your voice...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
