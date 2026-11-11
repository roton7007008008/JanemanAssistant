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
import org.json.JSONObject
import java.util.Locale

class AssistantService : Service(), TextToSpeech.OnInitListener {

    // >>>>> আপনার API KEY টি নিচের কোটেশনের ভেতরে বসাবেন <<<<<
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
            // বাংলা বা ইংরেজি দুটোই সুন্দরভাবে বলার চেষ্টা করবে
            val result = tts?.setLanguage(Locale("bn", "BD"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            speak("আমি রেডি আছি বস, বলুন কী করতে হবে?")
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

    // অ্যাপের ভেতরের স্ক্রিনে লেখা পাঠানোর ফাংশন
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
                        processEverythingWithAI(command)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {}
    }

    // মুখ দিয়ে যা বলবেন, Gemini AI বুঝে কাজ করবে এবং মুখে উত্তর দেবে
    private fun processEverythingWithAI(command: String) {
        broadcastTelemetry("You: \"$command\"", "AI: উত্তর ভাবছি...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val model = GenerativeModel(modelName = "gemini-1.5-flash", apiKey = GEMINI_API_KEY)

                val prompt = """
                    You are a smart phone assistant named Jarvis. The user said: "$command"
                    If the user is asking a question or just talking, reply warmly in Bengali in maximum 1-2 short sentences.
                    
                    Return a JSON strictly in this format (no extra text):
                    {
                      "action": "reply" or "youtube" or "volume" or "call" or "scroll" or "messenger" or "open",
                      "speech": "What you will say out loud in Bengali",
                      "target": "song name, contact name, app name, up/down, or empty"
                    }
                """.trimIndent()

                val res = model.generateContent(prompt).text ?: ""
                val cleanJson = res.substringAfter("{").substringBeforeLast("}")
                val json = JSONObject("{$cleanJson}")

                val action = json.optString("action", "reply")
                val speech = json.optString("speech", "কাজটি করছি বস")
                val target = json.optString("target", "")

                launch(Dispatchers.Main) {
                    // ১. রোবট মুখে উত্তর দেবে
                    speak(speech)
                    // ২. অ্যাপের স্ক্রিনে লেখা দেখাবে
                    broadcastTelemetry("You: $command", "Jarvis: $speech")

                    // ৩. কাজের নির্দেশ হলে সেই কাজ করবে
                    executeSystemAction(action, target)
                }

            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    speak("নেটে একটু সমস্যা হচ্ছে বস, আবার বলুন")
                    broadcastTelemetry("You: $command", "AI: Network error, retrying...")
                }
            }
        }
    }

    private fun executeSystemAction(action: String, target: String) {
        when (action) {
            "youtube" -> {
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", if (target.isEmpty()) "Ashes band" else target)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try { startActivity(intent) } catch (e: Exception) {}
            }
            "volume" -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val direction = if (target.contains("down") || target.contains("low")) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
            "call" -> {
                makeCall(target, 1)
            }
            "scroll" -> {
                MyAccessibilityService.instance?.scrollDown()
            }
            "messenger" -> {
                MyAccessibilityService.instance?.typeAndSendMessage(target)
            }
            "open" -> {
                openAnyAppByName(target)
            }
        }
    }

    // যেকোনো অ্যাপের নাম বললে খুঁজে বের করে ওপেন করা
    private fun openAnyAppByName(appName: String) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(appName.lowercase())) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                launchIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    startActivity(launchIntent)
                    return
                } catch (e: Exception) {}
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
            .setContentText("Awaiting your voice command...")
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
