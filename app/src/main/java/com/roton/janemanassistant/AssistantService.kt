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
            speak("আমি Janeman Assistant বলছি। আমি আপনার সাহায্যের জন্য প্রস্তুত আছি!")
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
                        handleCommand(command)
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

    private fun handleCommand(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You: \"$command\"")

        // ১. থামতে বললে থামবে
        if (lower.contains("চুপ") || lower.contains("থামো") || lower.contains("stop")) {
            tts?.stop()
            broadcastTelemetry("Janeman: (নীরব)")
            return
        }

        // ২. কে বানিয়েছে?
        if (lower.contains("বানিয়েছে") || lower.contains("মালিক") || lower.contains("তৈরি করেছে") || lower.contains("creator")) {
            val reply = "আমাকে বানিয়েছেন MD ROTON PRAMANIK! আমি ওনার তৈরি বিশেষ এআই।"
            speak(reply)
            broadcastTelemetry("Janeman: $reply")
            return
        }

        // ৩. টিকটক ও ভিডিও স্ক্রল/পাল্টানো
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ") || 
            lower.contains("পাল্টাও") || lower.contains("পরের ভিডিও")) {
            speak("স্যার, স্ক্রল করছি")
            MyAccessibilityService.instance?.scrollDown()
            return
        }

        // ৪. ইউটিউব গান
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান") || lower.contains("song")) {
            val query = command.replace("youtube", "", ignoreCase = true)
                .replace("ইউটিউব", "").replace("ওপেন করো", "").replace("গান ছাড়ো", "").replace("গান চালাও", "").trim()
            val finalQuery = if (query.isEmpty()) "Ashes band songs" else query
            speak("স্যার, ইউটিউবে $finalQuery ছাড়া হচ্ছে")
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

        // ৫. কল দেওয়া (ইংলিশ ও বাংলা নাম)
        if (lower.contains("call") || lower.contains("কল")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val cleanTarget = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("call", "", ignoreCase = true).replace("sim 2", "").replace("sim 1", "").replace("কে", "").trim()
            speak("স্যার, $cleanTarget কে কল দিচ্ছি")
            smartPhoneCall(cleanTarget, sim)
            return
        }

        // ৬. অ্যাপ ওপেন (সব রকম শব্দে)
        if (lower.contains("খোলো") || lower.contains("ওপেন") || lower.contains("চালাও") || lower.contains("যাও") || lower.contains("ঢোকো") || lower.contains("open")) {
            val appName = command.replace("খোলো", "").replace("ওপেন করো", "").replace("চালাও", "")
                .replace("যাও", "").replace("ঢোকো", "").replace("open", "", ignoreCase = true)
                .replace("অ্যাপে", "").replace("অ্যাপসে", "").replace("এ", "").trim()

            if (openUniversalApp(appName)) {
                speak("স্যার, $appName খুলে দিয়েছি")
                return
            }
        }

        // ৭. সাধারণ সব কথার মিষ্টি উত্তর (Gemini AI)
        talkWithGeminiAi(command)
    }

    private fun smartPhoneCall(target: String, simSlot: Int) {
        val cr = contentResolver
        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return

        val cleanQuery = target.lowercase().trim()
        val englishSound = cleanQuery.replace("মিম", "mim").replace("রতন", "roton")
        var bestNumber: String? = null

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx)?.lowercase() ?: ""
                val num = it.getString(numIdx) ?: ""
                if (name.contains(cleanQuery) || name.contains(englishSound)) {
                    bestNumber = num
                    break
                }
            }
        }

        if (bestNumber != null) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = telecomManager.callCapablePhoneAccounts
            val selectedAccount: PhoneAccountHandle? = if (simSlot == 2 && accounts.size > 1) accounts[1] else accounts.firstOrNull()

            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$bestNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                selectedAccount?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
            try { startActivity(callIntent) } catch (e: Exception) {}
        } else {
            speak("স্যার, কন্টাক্টে $target খুঁজে পাইনি")
        }
    }

    private fun openUniversalApp(appName: String): Boolean {
        val lower = appName.lowercase()
        val pm = packageManager
        if (lower.contains("tiktok") || lower.contains("টিকটক")) return openDirectApp("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        if (lower.contains("facebook") || lower.contains("ফেসবুক")) return openDirectApp("com.facebook.katana")
        if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) return openDirectApp("com.whatsapp")
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার")) return openDirectApp("com.facebook.orca")
        if (lower.contains("bkash") || lower.contains("বিকাশ")) return openDirectApp("com.bKash.customerapp")

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

    private fun talkWithGeminiAi(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)
                val prompt = "You are Janeman Assistant, created by MD ROTON PRAMANIK. Always address the user politely as 'Sir' (স্যার) in Bengali. Answer this question naturally, concisely in 1-2 Bengali sentences: $command"
                val res = model.generateContent(prompt).text ?: ""
                val cleanAnswer = res.trim()
                
                launch(Dispatchers.Main) {
                    speak(cleanAnswer)
                    broadcastTelemetry("Janeman: $cleanAnswer")
                }
            } catch (e: Exception) {
                // এরর হলে জোর করে কোনো আজগুবি কথা বলবে না
            }
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
            .setContentTitle("JANEMAN ONLINE")
            .setContentText("Created by MD ROTON PRAMANIK")
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
