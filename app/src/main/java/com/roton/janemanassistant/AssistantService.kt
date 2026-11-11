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
import android.os.IBinder
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class AssistantService : Service() {

    // >>>>> আপনার সেভ করা GEMINI API KEY টি নিচের কোটেশনের ভেতরে বসাবেন <<<<<
    private val GEMINI_API_KEY = "AQ.Ab8RN6LwpcqBwrQg-FX2RQbRVR_1lZFo8rWYwjzTfFxbgknvlQ"

    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(101, buildNotification())
        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
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
                    // কোনো সমস্যা হলে আবার অটোমেটিক কথা শোনা শুরু করবে
                    if (isListening) startListening()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val userSaid = matches[0]
                        processVoiceCommand(userSaid)
                    }
                    if (isListening) startListening()
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
        speechRecognizer?.startListening(intent)
    }

    // মুখ দিয়ে যা বলবেন তা Gemini AI বুঝবে এবং সিদ্ধান্ত নেবে
    private fun processVoiceCommand(command: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = GEMINI_API_KEY
                )

                val prompt = """
                    You are a phone assistant. Analyze this voice command: "$command"
                    Return ONLY a JSON response without code blocks:
                    - If user wants to play a song/search youtube: {"action": "youtube", "query": "song name"}
                    - If volume increase/decrease: {"action": "volume", "type": "up" or "down"}
                    - If call someone: {"action": "call", "name": "contact name", "sim": 1 or 2}
                    - If scroll tiktok/reels: {"action": "scroll"}
                    - If messenger message: {"action": "messenger", "message": "text"}
                    - If open app: {"action": "open", "app": "app name"}
                """.trimIndent()

                val response = generativeModel.generateContent(prompt).text ?: ""
                val cleanJson = response.replace("```json", "").replace("```", "").trim()
                val json = JSONObject(cleanJson)

                launch(Dispatchers.Main) {
                    executeAction(json)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // AI এর নির্দেশ অনুযায়ী কাজ সম্পাদন
    private fun executeAction(json: JSONObject) {
        when (json.optString("action")) {
            "youtube" -> {
                val query = json.optString("query")
                val intent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            "volume" -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val direction = if (json.optString("type") == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
            "call" -> {
                val name = json.optString("name")
                val sim = json.optInt("sim", 1)
                makeCall(name, sim)
            }
            "scroll" -> {
                MyAccessibilityService.instance?.scrollDown()
            }
            "messenger" -> {
                val message = json.optString("message")
                MyAccessibilityService.instance?.typeAndSendMessage(message)
            }
        }
    }

    // সিম ১ বা সিম ২ নির্বাচন করে কল দেওয়া
    private fun makeCall(contactName: String, simSlot: Int) {
        val number = getPhoneNumber(contactName) ?: return
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecomManager.callCapablePhoneAccounts

        val selectedAccount: PhoneAccountHandle? = if (simSlot == 2 && accounts.size > 1) {
            accounts[1] // SIM 2
        } else if (accounts.isNotEmpty()) {
            accounts[0] // SIM 1
        } else null

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            selectedAccount?.let {
                putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
        }
        startActivity(callIntent)
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
        cursor?.use {
            if (it.moveToFirst()) {
                number = it.getString(0)
            }
        }
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
            .setContentTitle("Janeman Assistant Active")
            .setContentText("Listening to your commands in background...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
