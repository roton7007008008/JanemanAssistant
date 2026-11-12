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
import android.speech.tts.UtteranceProgressListener
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
    private var isTtsSpeaking = false
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
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                }
                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    // রোবটের নিজের কথা বলা শেষ হলে তবেই আবার কান অন করবে (রিপিট বন্ধ করার জন্য)
                    mainHandler.postDelayed({ if (isListening) startListening() }, 500)
                }
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                }
            })
            speak("আমি Janeman Assistant বলছি। আমি প্রস্তুত আছি, স্যার!")
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            isTtsSpeaking = true
            speechRecognizer?.stopListening()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
        }
    }

    private fun silenceImmediately() {
        mainHandler.post {
            tts?.stop()
            isTtsSpeaking = false
            broadcastTelemetry("Jarvis: (নীরব আছি স্যার)")
            if (isListening) startListening()
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
                    // কোনো নয়েজ হলে চুপচাপ থাকবে, বারবার কোনো এরর সাউন্ড করবে না
                    if (isListening && !isTtsSpeaking) {
                        mainHandler.postDelayed({ startListening() }, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && !isTtsSpeaking) {
                        val command = matches[0].trim()
                        // খুব ছোট বা আজেবাজে শব্দ হলে এড়িয়ে যাবে
                        if (command.length >= 2) {
                            handleCommandMaster(command)
                        } else {
                            if (isListening) startListening()
                        }
                    } else {
                        if (isListening) startListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (isTtsSpeaking) return
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }
        try { speechRecognizer?.startListening(intent) } catch (e: Exception) {}
    }

    private fun handleCommandMaster(command: String) {
        val lower = command.lowercase(Locale.ROOT)
        broadcastTelemetry("You: \"$command\"")

        // ১. চুপ করো / থামো বললে সাথে সাথে কথা বন্ধ
        if (lower.contains("চুপ") || lower.contains("থামো") || lower.contains("থাম") || 
            lower.contains("stop") || lower.contains("quiet") || lower.contains("বন্ধ হও")) {
            silenceImmediately()
            return
        }

        // ২. কে বানিয়েছে?
        if (lower.contains("বানিয়েছে") || lower.contains("তৈরি করেছে") || lower.contains("মালিক") || lower.contains("creator") || lower.contains("made you")) {
            val reply = "আমাকে বানিয়েছেন MD ROTON PRAMANIK! আমি ওনার তৈরি বিশেষ এআই।"
            speak(reply)
            broadcastTelemetry("Janeman: $reply")
            return
        }

        // ৩. টিকটক সোয়াইপ, ভিডিও চেঞ্জ ও পাল্টাও
        if (lower.contains("scroll") || lower.contains("স্ক্রল") || lower.contains("সোয়াইপ") ||
            lower.contains("পাল্টাও") || lower.contains("বদলাও") || lower.contains("ভিডিও চেঞ্জ") ||
            lower.contains("পরের ভিডিও") || lower.contains("next")) {
            speak("ভিডিও পাল্টাচ্ছি স্যার")
            MyAccessibilityService.instance?.scrollDown()
            broadcastTelemetry("Janeman: Video Scrolled.")
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

        // ৫. কল দেওয়া (ইংলিশ নাম, Mim 01 ফিক্স)
        if (lower.contains("call") || lower.contains("কল") || lower.contains("ফোন")) {
            val sim = if (lower.contains("২") || lower.contains("2")) 2 else 1
            val cleanTarget = command.replace("কল দাও", "").replace("কল করো", "")
                .replace("ফোন দাও", "").replace("call", "", ignoreCase = true)
                .replace("sim 2", "").replace("sim 1", "").replace("কে", "").replace("রে", "").trim()

            speak("স্যার, $cleanTarget কে কল দিচ্ছি")
            smartPhoneCall(cleanTarget, sim)
            return
        }

        // ৬. যেকোনো অ্যাপ ওপেন করা (সব ধরনের শব্দের সাপোর্ট)
        val appKeywords = listOf("খোলো", "খোল", "ওপেন", "চালাও", "চালু করো", "যাও", "নিয়ে যাও", "ঢোকো", "ঢুকো", "open", "launch")
        var isAppCommand = false
        var targetAppName = command

        for (kw in appKeywords) {
            if (lower.contains(kw)) {
                isAppCommand = true
                targetAppName = targetAppName.replace(kw, "", ignoreCase = true)
            }
        }

        if (isAppCommand) {
            val cleanApp = targetAppName.replace("অ্যাপে", "").replace("অ্যাপসে", "")
                .replace("apps a", "").replace("app e", "").replace("এ", "").replace("তে", "").trim()

            if (openUniversalApp(cleanApp)) {
                speak("স্যার, $cleanApp খুলে দিয়েছি")
                broadcastTelemetry("Janeman: Opened $cleanApp")
                return
            }
        }

        // ৭. সাধারণ চ্যাট (Gemini AI)
        talkWithGeminiAi(command)
    }

    private fun smartPhoneCall(target: String, simSlot: Int) {
        val digitsOnly = target.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 6) {
            dialNumber(digitsOnly, simSlot)
            return
        }

        val resolvedNumber = findContactNumberSmart(target)
        if (resolvedNumber != null) {
            dialNumber(resolvedNumber, simSlot)
        } else {
            speak("স্যার, কন্টাক্টে $target পাওয়া যায়নি")
        }
    }

    private fun findContactNumberSmart(queryName: String): String? {
        val cr = contentResolver
        val cursor = cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return null

        val cleanQuery = queryName.lowercase().trim()
        val englishPhonetic = convertBanglaToEnglishSound(cleanQuery)
        var bestMatchNumber: String? = null

        cursor.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val contactName = it.getString(nameIndex)?.lowercase() ?: ""
                val contactNumber = it.getString(numIndex) ?: ""

                if (contactName.contains(cleanQuery) || (englishPhonetic.isNotEmpty() && contactName.contains(englishPhonetic))) {
                    bestMatchNumber = contactNumber
                    break
                }
            }
        }
        return bestMatchNumber
    }

    private fun convertBanglaToEnglishSound(bangla: String): String {
        return bangla.replace("মিম", "mim")
            .replace("রতন", "roton")
            .replace("আম্মু", "ammu")
            .replace("মা", "mom")
            .replace("বাবা", "baba")
            .replace("ভাই", "bhai")
            .replace("সাকিব", "sakib")
    }

    private fun dialNumber(number: String, simSlot: Int) {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecomManager.callCapablePhoneAccounts
        val selectedAccount: PhoneAccountHandle? = if (simSlot == 2 && accounts.size > 1) accounts[1] else accounts.firstOrNull()

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            selectedAccount?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        try { startActivity(callIntent) } catch (e: Exception) {}
    }

    // নিখুঁত ইউনিভার্সাল অ্যাপ ওপেনার
    private fun openUniversalApp(appName: String): Boolean {
        val lower = appName.lowercase()
        val pm = packageManager

        if (lower.contains("tiktok") || lower.contains("টিকটক")) return openDirectApp("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        if (lower.contains("facebook") || lower.contains("ফেসবুক")) return openDirectApp("com.facebook.katana")
        if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) return openDirectApp("com.whatsapp")
        if (lower.contains("messenger") || lower.contains("মেসেঞ্জার")) return openDirectApp("com.facebook.orca")
        if (lower.contains("bkash") || lower.contains("বিকাশ")) return openDirectApp("com.bKash.customerapp")
        if (lower.contains("free fire") || lower.contains("ফ্রি ফায়ার")) return openDirectApp("com.dts.freefireth", "com.dts.freefiremax")

        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(lower) || app.packageName.lowercase().contains(lower)) {
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
                // কোনো নয়েজ বা নেটের ভুল হলে আজেবাজে কথা না বলে চুপ থাকবে
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
            .setContentTitle("JANEMAN HUD ONLINE")
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
