package com.timin.workoutinator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Tts {

    // ------------------------------------------------------------------ cache

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "tts").apply { mkdirs() }

    private fun key(text: String, lang: String): String {
        val md = MessageDigest.getInstance("MD5").digest("$lang|$text".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cachedFile(ctx: Context, text: String): File? {
        val lang = langOf(text)
        val base = key(text, lang)
        return listOf("mp3", "wav")
            .map { File(cacheDir(ctx), "$base.$it") }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    fun langOf(text: String): String =
        if (text.any { it in '\u0590'..'\u05FF' }) "iw" else "en"

    // ------------------------------------------------------------- preparation

    /**
     * Ensure every phrase has a cached audio file.
     * Tries gTTS (Google Translate endpoint) first, falls back to device TTS.
     * Returns list of phrases that could not be synthesized at all.
     */
    fun prepare(
        ctx: Context,
        phrases: List<String>,
        onProgress: (done: Int, total: Int, viaFallback: Int) -> Unit
    ): List<String> {
        val failures = mutableListOf<String>()
        var fallbackCount = 0
        val todo = phrases.distinct()
        todo.forEachIndexed { idx, phrase ->
            if (cachedFile(ctx, phrase) == null) {
                val lang = langOf(phrase)
                val ok = fetchGtts(ctx, phrase, lang) || run {
                    val dev = deviceTtsToFile(ctx, phrase, lang)
                    if (dev) fallbackCount++
                    dev
                }
                if (!ok) failures.add(phrase)
            }
            onProgress(idx + 1, todo.size, fallbackCount)
        }
        return failures
    }

    private fun fetchGtts(ctx: Context, text: String, lang: String): Boolean {
        if (text.length > 190) return false // endpoint limit; let device TTS handle it
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = URL(
                "https://translate.google.com/translate_tts" +
                        "?ie=UTF-8&client=tw-ob&tl=$lang&q=$q"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val ok = conn.responseCode == 200
            if (ok) {
                val out = File(cacheDir(ctx), key(text, lang) + ".mp3")
                conn.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                out.length() > 0
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------ device TTS fallback

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun ensureTts(ctx: Context): Boolean {
        if (tts != null) return ttsReady
        val latch = CountDownLatch(1)
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return ttsReady
    }

    private fun deviceTtsToFile(ctx: Context, text: String, lang: String): Boolean {
        if (!ensureTts(ctx)) return false
        val engine = tts ?: return false
        val loc = if (lang == "iw") Locale("iw", "IL") else Locale.ENGLISH
        val avail = engine.setLanguage(loc)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED)
            return false

        val out = File(cacheDir(ctx), key(text, lang) + ".wav")
        val latch = CountDownLatch(1)
        var success = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { success = true; latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { latch.countDown() }
        })
        engine.synthesizeToFile(text, Bundle(), out, key(text, lang))
        latch.await(15, TimeUnit.SECONDS)
        return success && out.exists() && out.length() > 0
    }

    // --------------------------------------------------------------- playback

    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Play a cached utterance, pausing other audio (transient focus) for its
     * duration. Blocks until playback finishes. Returns false if the file was
     * missing or playback failed.
     */
    fun speakBlocking(ctx: Context, text: String, stopFlag: () -> Boolean): Boolean {
        val file = cachedFile(ctx, text) ?: return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAttrs)
            .build()
        val granted = am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(speechAttrs)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare()
            mp.start()
            // poll so Stop can cut speech short
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                if (stopFlag()) { mp.stop(); break }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { mp.release() }
            if (granted) am.abandonAudioFocusRequest(focus)
        }
    }

    // ------------------------------------------------------------------- sfx

    private var soundPool: SoundPool? = null
    private var sfxDelayStart = 0
    private var sfxDelayEnd = 0
    private var sfxRep = 0

    fun initSfx(ctx: Context) {
        if (soundPool != null) return
        val sp = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        sfxDelayStart = sp.load(ctx, R.raw.delay_indicator, 1)
        sfxDelayEnd = sp.load(ctx, R.raw.delay_end, 1)
        sfxRep = sp.load(ctx, R.raw.rep_count, 1)
        soundPool = sp
    }

    // Beeps intentionally do NOT take audio focus: they mix over Spotify.
    fun beepDelayStart() { soundPool?.play(sfxDelayStart, 1f, 1f, 1, 0, 1f) }
    fun beepDelayEnd() { soundPool?.play(sfxDelayEnd, 1f, 1f, 1, 0, 1f) }
    fun beepRep() { soundPool?.play(sfxRep, 1f, 1f, 1, 0, 1f) }

    fun shutdown() {
        tts?.shutdown(); tts = null; ttsReady = false
        soundPool?.release(); soundPool = null
    }
}
