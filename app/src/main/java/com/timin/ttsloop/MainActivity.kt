package com.timin.ttsloop

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var textInput: EditText
    private lateinit var pauseInput: EditText
    private lateinit var loopCheck: CheckBox
    private lateinit var duckCheck: CheckBox
    private lateinit var startStopButton: Button
    private lateinit var status: TextView

    private var running = false
    private var ttsReady = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // If something else (a phone call, another media app) takes focus from us, stop cleanly.
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            handler.post { stopSpeaking("Stopped: lost audio focus") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply { text = "Text to speak:" })
        textInput = EditText(this).apply {
            hint = "Type something…"
            setText("Hello from TTS Loop")
            minLines = 2
        }
        root.addView(textInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        loopCheck = CheckBox(this).apply { text = "Loop"; isChecked = true }
        root.addView(loopCheck)

        root.addView(TextView(this).apply { text = "Pause between repeats (seconds):" })
        pauseInput = EditText(this).apply {
            setText("2")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(pauseInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        duckCheck = CheckBox(this).apply {
            text = "Duck other audio (unchecked = pause it)"
            isChecked = true
        }
        root.addView(duckCheck)

        startStopButton = Button(this).apply {
            text = "Start"
            setOnClickListener { if (running) stopSpeaking("Stopped") else startSpeaking() }
        }
        root.addView(startStopButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply { text = "Initializing TTS…" }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onInit(initStatus: Int) {
        ttsReady = initStatus == TextToSpeech.SUCCESS
        handler.post {
            status.text = if (ttsReady) "Ready" else "TTS engine failed to initialize"
        }
        if (!ttsReady) return

        // Route TTS output as media so it participates in the normal music audio stream.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                handler.post {
                    if (!running) return@post
                    if (loopCheck.isChecked) {
                        val pauseMs = ((pauseInput.text.toString().toDoubleOrNull()
                            ?: 0.0) * 1000).toLong().coerceAtLeast(0)
                        status.text = "Waiting ${pauseMs / 1000.0}s…"
                        handler.postDelayed({ if (running) speakOnce() }, pauseMs)
                    } else {
                        stopSpeaking("Done")
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post { stopSpeaking("TTS error") }
            }
        })
    }

    private fun startSpeaking() {
        if (!ttsReady) {
            status.text = "TTS not ready yet"
            return
        }
        val focusType = if (duckCheck.isChecked)
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        else
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT

        val request = AudioFocusRequest.Builder(focusType)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val granted = audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            status.text = "Audio focus denied (call in progress?)"
            return
        }
        focusRequest = request
        running = true
        startStopButton.text = "Stop"
        speakOnce()
    }

    private fun speakOnce() {
        status.text = "Speaking…"
        tts.speak(textInput.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "utt")
    }

    private fun stopSpeaking(message: String) {
        if (!running && focusRequest == null) return
        running = false
        handler.removeCallbacksAndMessages(null)
        tts.stop()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        startStopButton.text = "Start"
        status.text = message
    }

    override fun onDestroy() {
        stopSpeaking("Stopped")
        tts.shutdown()
        super.onDestroy()
    }
}
