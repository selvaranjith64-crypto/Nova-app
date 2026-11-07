package com.nova.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NovaService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var awaitingUntil = 0L
    private val history = mutableListOf<Pair<String, String>>()
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter.",
        "Why don't scientists trust atoms? They make up everything.",
        "I told my phone I needed space. Now it says storage full."
    )

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    private fun buildNotif(text: String): Notification {
        return Notification.Builder(this, "nova")
            .setContentTitle("Nova is listening")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif("Say \"Nova\" and a command")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        muteBeep(false)
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { speaking = false }
            })
            ttsReady = true
        }
    }

    private fun muteBeep(mute: Boolean) {
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try { audio.adjustStreamVolume(s, dir, 0) } catch (e: Exception) {}
        }
    }

    private fun startListening() {
        if (!running) return
        if (speaking) { scheduleRestart(700); return }
        muteBeep(true)
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(i)
    }

    private fun scheduleRestart(delay: Long = 300) {
        handler.postDelayed({ startListening() }, delay)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) onHeard(text)
        scheduleRestart(if (speaking) 900 else 300)
    }

    override fun onError(error: Int) {
        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun onHeard(raw: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotif("Heard: $raw"))
        val t = raw.lowercase().replace(Regex("[.,!?]"), "").trim()
        val now = System.currentTimeMillis()
        val wake = Regex("\\b(nova|noah|nover|novah|nowa|no va|know va)\\b").find(t)
        val cmd = when {
            wake != null -> t.substring(wake.range.last + 1).trim()
            now < awaitingUntil -> t
            else -> return
        }
        awaitingUntil = 0
        if (cmd.isEmpty()) {
            awaitingUntil = now + 9000
            say("Yes?")
            return
        }
        handle(cmd)
        if (awaitingUntil == 0L) awaitingUntil = System.currentTimeMillis() + 10000
    }

    private fun handle(cmd: String) {
        if (Regex("^(stop|cancel|quiet|be quiet)$").matches(cmd)) { tts?.stop(); speaking = false; return }
        if (Regex("^(goodbye|bye|exit|stop listening|shut down)$").matches(cmd)) {
            say("Goodbye. Talk to you later."); handler.postDelayed({ stopSelf() }, 2500); return
        }
        if (Regex("what('s| is) the time|what time is it").containsMatchIn(cmd)) {
            say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())); return
        }
        if (Regex("today's date|what('s| is) the date|what day is it").containsMatchIn(cmd)) {
            say("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())); return
        }
        if (cmd.contains("joke")) { say(jokes[(System.currentTimeMillis() % jokes.size).toInt()]); return }

        val timer = Regex("timer (?:for )?(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?").find(cmd)
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*(second|sec|minute|min|hour)s?\\s+timer").find(cmd)
        if (timer != null) {
            val n = timer.groupValues[1].toDouble()
            val unit = timer.groupValues[2]
            val ms = (n * when { unit.startsWith("h") -> 3600000 ; unit.startsWith("m") -> 60000 ; else -> 1000 }).toLong()
            val word = when { unit.startsWith("h") -> "hour"; unit.startsWith("m") -> "minute"; else -> "second" }
            val label = (if (n == n.toInt().toDouble()) n.toInt().toString() else n.toString()) + " " + word + (if (n == 1.0) "" else "s")
            handler.postDelayed({
                try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500) } catch (e: Exception) {}
                say("Your $label timer is done.")
            }, ms)
            say("Timer set for $label.")
            return
        }

        Regex("^(?:play|put on|start)\\s+(.+?)\\s+(?:on|in|using)\\s+spotify$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^spotify\\s+(?:play\\s+)?(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^play\\s+(.+)$").find(cmd)?.let { playSpotify(it.groupValues[1]); return }
        Regex("^(?:call|phone|dial|ring)\\s+(.+)$").find(cmd)?.let { callPerson(it.groupValues[1].removePrefix("to ").trim()); return }
        Regex("^(?:open|launch|start|go to)\\s+(.+)$").find(cmd)?.let { openApp(it.groupValues[1]); return }
        Regex("^(?:search(?: for)?|google|look up)\\s+(.+)$").find(cmd)?.let {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(it.groupValues[1]))))
            say("Searching for " + it.groupValues[1] + "."); return
        }

        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) {
            say("I can only do commands right now. To chat with me, open the Nova app and paste your A I key.")
        } else {
            askAI(cmd, key)
        }
    }

    private fun askAI(q: String, key: String) {
        Thread {
            try {
                val msgs = JSONArray()
                history.takeLast(8).forEach { p -> msgs.put(JSONObject().put("role", p.first).put("content", p.second)) }
                msgs.put(JSONObject().put("role", "user").put("content", q))
                val body = JSONObject()
                    .put("model", "claude-haiku-5-5")
                    .put("max_tokens", 300)
                    .put("system", "You are Nova, a warm, friendly voice assistant living on the user's Android phone. Talk like a caring human friend, in one to three short spoken sentences. No markdown, no lists, no emojis. If the user wants a phone action like opening an app, calling someone or playing music, tell them to say it as a command, for example: open WhatsApp.")
                    .put("messages", msgs)
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val txt = stream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) {
                    handler.post {
                        say(if (code == 401) "My A I key looks wrong. Please check it in the app." else "I could not reach my brain. Error $code.")
                    }
                    return@Thread
                }
                val reply = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
                history.add("user" to q)
                history.add("assistant" to reply)
                handler.post {
                    say(reply)
                    awaitingUntil = System.currentTimeMillis() + 15000
                }
            } catch (e: Exception) {
                handler.post { say("I could not connect. Please check your internet.") }
            }
        }.start()
    }

    private fun playSpotify(query: String) {
        say("Playing $query on Spotify.")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        if (!launch(play)) launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
    }

    private fun callPerson(who: String) {
        val digits = who.replace(" ", "")
        val number = if (Regex("^\\+?\\d{3,}$").matches(digits)) digits else findNumber(who)
        if (number == null) { say("I could not find $who in your contacts."); return }
        say("Calling $who.")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val canCall = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        launch(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun openApp(spoken: String) {
        val name = norm(spoken.removePrefix("the ").removeSuffix(" app"))
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to norm(it.loadLabel(pm).toString()) }
        val match = apps.firstOrNull { it.second == name }
            ?: apps.firstOrNull { it.second.startsWith(name) }
            ?: apps.firstOrNull { name.length >= 3 && it.second.contains(name) }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.first) }
        if (intent != null) {
            say("Opening $spoken.")
            launch(intent)
        } else {
            say("I could not find an app called $spoken.")
        }
    }

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun say(text: String) {
        if (!ttsReady) return
        speaking = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova")
    }
}
