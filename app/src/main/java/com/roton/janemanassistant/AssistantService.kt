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
            speak("আমি রেডি আছি বস!")
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
                    broadcastTelemetry("User: (শুনছি... বলুন)", "AI: কান খাড়া করে আছি...")
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
                        handleSmartCommand(command)
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

    // অফলাইন সুপার ফাস্ট কমান্ড প্রসেসর (ইন্টারনেট ছাড়াও কাজ করবে!)
    private fun handleSmartCommand(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You: \"$command\"", "AI: নির্দেশ পালন করছি...")

        // ১. ইউটিউব গান
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান") || lower.contains("song")) {
            val query = command.replace("youtube", "", ignoreCase = true)
                .replace("ইউটিউব", "").replace("ওপেন করো", "").replace("গান ছাড়ো", "").replace("গান চালাও", "").trim()
            val finalQuery = if (query.isEmpty()) "Ashes band songs" else query
            speak("ইউটিউবে $finalQuery ছাড়া হচ্ছে")
            broadcastTelemetry("You: $command", "AI: YouTube -> $finalQuery")

            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", finalQuery)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$finalQuery")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(web)
            }
            return
        }

        // ২. ভলিউম বাড়ানো / কমানো
        if (lower.contains("volume") || lower.contains("ভলিউম") || lower.contains("আওয়াজ")) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (lower.contains("কমাও") || lower.contains("down") || lower.contains("low")) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                speak("ভলিউম কমিয়ে দিয়েছি")
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                speak("ভলিউম বাড়িয়ে দিয়েছি")
            }
            return
        }

        // ৩. টিকটক সোয়াইপ
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ") || lower.contains("tiktok")) {
            speak("স্ক্রল করছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৪. মেসেঞ্জার
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার") || lower.contains("মেসেজ")) {
            val msg = command.replace("মেসেঞ্জার", "").replace("মেসেজ", "").replace("পাঠাও", "").trim()
            speak("মেসেঞ্জারে টাইপ করছি")
            MyAccessibilityService.instance?.typeAndSendMessage(msg)
            return
        }

        // ৫. কল দেওয়া (সিম ১ বা ২)
        if (lower.contains("call") || lower.contains("কল")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val name = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "").replace("sim 2", "").replace("sim 1", "").trim()
            speak("$name কে সিম $sim দিয়ে কল দিচ্ছি")
            makeCall(name, sim)
            return
        }

        // ৬. যেকোনো অ্যাপ ওপেন করা
        if (lower.contains("open") || lower.contains("ওপেন") || lower.contains("যাও") || lower.contains("খোলো")) {
            val appName = command.replace("open", "", ignoreCase = true)
                .replace("ওপেন করো", "").replace("খোলো", "").replace("যাও", "").trim()
            val opened = openAnyAppByName(appName)
            if (opened) {
                speak("$appName খুলে দিয়েছি")
                return
            }
        }

        // সাধারণ প্রশ্নের জন্য Gemini AI ব্যাকআপ
        callGeminiFallback(command)
    }

    private fun openAnyAppByName(appName: String): Boolean {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(appName.lowercase())) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                launchIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    startActivity(launchIntent)
                    return true
                } catch (e: Exception) {}
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
                speak("আমি আপনার নির্দেশটি বুঝতে পেরেছি বস!")
                broadcastTelemetry("You: $command", "AI: Command noted.")
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
            val channel = NotificationChannel("assistant_channel", "Jarvis Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "assistant_channel")
            .setContentTitle("JARVIS ONLINE")
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
