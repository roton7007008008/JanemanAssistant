package com.roton.janemanassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
            speak("ইয়েস স্যার! জারভিস অনলাইন। বলুন আমি কী করতে পারি?")
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
                override fun onReadyForSpeech(params: Bundle?) {
                    broadcastTelemetry("User: (শুনছি স্যার... বলুন)", "AI: Listening...")
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
                        handleSmartMasterEngine(command)
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

    // মাস্টার ইঞ্জিন: সিস্টেম কাজ + Gemini AI চ্যাট
    private fun handleSmartMasterEngine(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You: \"$command\"", "AI: প্রসেস করছি...")

        // ১. ইউটিউব প্লে
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান") || lower.contains("song")) {
            val query = command.replace("youtube", "", ignoreCase = true)
                .replace("ইউটিউব", "").replace("ওপেন করো", "").replace("গান ছাড়ো", "").replace("গান চালাও", "").trim()
            val finalQuery = if (query.isEmpty()) "Ashes band songs" else query
            speak("স্যার, ইউটিউবে $finalQuery চালিয়ে দিচ্ছি")
            broadcastTelemetry("You: $command", "AI: Playing YouTube -> $finalQuery")

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

        // ২. টিকটক ওপেন ও স্ক্রল
        if (lower.contains("tiktok") || lower.contains("টিকটক")) {
            if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ")) {
                if (MyAccessibilityService.instance == null) {
                    speak("স্যার, অনুগ্রহ করে অ্যাপে গিয়ে অ্যাক্সেসিবিলিটি অন করুন")
                    return
                }
                speak("স্যার, স্ক্রল করছি")
                MyAccessibilityService.instance?.scrollDown()
                return
            } else {
                speak("স্যার, টিকটক খুলে দিচ্ছি")
                openDirectApp("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
                return
            }
        }

        // ৩. সাধারণ স্ক্রল
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ")) {
            if (MyAccessibilityService.instance == null) {
                speak("স্যার, আগে অ্যাক্সেসিবিলিটি অন করে নিন")
                return
            }
            speak("স্যার, স্ক্রল করে দিয়েছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৪. মেসেঞ্জার
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার") || lower.contains("মেসেজ")) {
            val msg = command.replace("মেসেঞ্জার", "").replace("মেসেজ", "").replace("পাঠাও", "").replace("লেখো", "").trim()
            if (MyAccessibilityService.instance == null) {
                speak("স্যার, অ্যাক্সেসিবিলিটি পারমিশন দরকার")
                return
            }
            speak("স্যার, মেসেঞ্জারে টাইপ করছি")
            MyAccessibilityService.instance?.typeAndSendMessage(msg)
            return
        }

        // ৫. কল দেওয়া (সিম ১ বা ২)
        if (lower.contains("call") || lower.contains("কল")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val rawName = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "").replace("sim 2", "").replace("sim 1", "").trim()
            // নামের শেষের 'কে' বা বিভক্তি কেটে ফেলা
            val cleanName = rawName.removeSuffix("কে").removeSuffix("রে").trim()
            speak("স্যার, $cleanName কে সিম $sim দিয়ে কল দিচ্ছি")
            makeCall(cleanName, sim)
            return
        }

        // ৬. যেকোনো অ্যাপ ওপেন (Facebook, WhatsApp, Camera ইত্যাদি)
        if (lower.contains("open") || lower.contains("ওপেন") || lower.contains("যাও") || lower.contains("খোলো")) {
            val appName = command.replace("open", "", ignoreCase = true)
                .replace("ওপেন করো", "").replace("খোলো", "").replace("যাও", "").trim()
            val opened = openUniversalApp(appName)
            if (opened) {
                speak("স্যার, $appName খুলে দিয়েছি")
                return
            }
        }

        // ৭. বাকি সব ক্ষেত্রে: Gemini AI এর সাথে সরাসরি কথা বলা (যেমন চ্যাটজিপিটি বা জেমিনাই)
        talkWithGeminiAi(command)
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
        // সরাসরি জনপ্রিয় অ্যাপগুলোর জন্য ফাস্ট লঞ্চার
        if (lower.contains("facebook") || lower.contains("ফেসবুক")) return openDirectApp("com.facebook.katana")
        if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) return openDirectApp("com.whatsapp")
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার")) return openDirectApp("com.facebook.orca")
        if (lower.contains("camera") || lower.contains("ক্যামেরা")) {
            val intent = Intent("android.media.action.IMAGE_CAPTURE").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            try { startActivity(intent); return true } catch (e: Exception) {}
        }

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

    // জীবন্ত Gemini AI উত্তর (সবসময় স্যার বলে কথা বলবে)
    private fun talkWithGeminiAi(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val prompt = "You are Jarvis, a very smart personal assistant for your boss. Address the user respectfully as 'Sir' (স্যার) in Bengali. Give a friendly, intelligent and direct answer in 1-2 Bengali sentences to: $command"
                val res = model.generateContent(prompt).text ?: ""
                val cleanAnswer = res.trim()
                
                launch(Dispatchers.Main) {
                    speak(cleanAnswer)
                    broadcastTelemetry("You: $command", "Jarvis: $cleanAnswer")
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    speak("স্যার, আপনার নির্দেশটি বুঝতে পেরেছি। দয়া করে আরেকবার বলুন।")
                }
            }
        }
    }

    private fun makeCall(contactName: String, simSlot: Int) {
        val number = getPhoneNumber(contactName)
        if (number == null) {
            speak("স্যার, কন্টাক্টে $contactName নামটি খুঁজে পাওয়া যায়নি")
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
            val channel = NotificationChannel("assistant_channel", "Jarvis Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "assistant_channel")
            .setContentTitle("JARVIS AI ONLINE")
            .setContentText("Awaiting command, Sir...")
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
