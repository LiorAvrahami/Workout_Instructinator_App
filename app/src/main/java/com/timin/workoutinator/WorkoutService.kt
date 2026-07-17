package com.timin.workoutinator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Runs one workout script as a foreground service so timing survives
 * screen-off. UI reads WorkoutService.state and calls the companion commands.
 */
class WorkoutService : Service() {

    enum class Phase { IDLE, PREPARING, SPEAKING, WAITING, PAUSED, DONE, ERROR }

    class State {
        @Volatile var phase = Phase.IDLE
        @Volatile var scriptName = ""
        @Volatile var currentInstruction = ""
        @Volatile var lastAnnouncement = ""
        @Volatile var nextInstruction = ""
        @Volatile var remainingSec = 0.0
        @Volatile var groupTotalSec = 0.0
        @Volatile var prepDone = 0
        @Volatile var prepTotal = 0
        @Volatile var prepFallback = 0
        @Volatile var warnings: List<String> = emptyList()
        @Volatile var error = ""
    }

    companion object {
        val state = State()
        @Volatile private var instance: WorkoutService? = null
        @Volatile private var stopRequested = false
        @Volatile private var pauseRequested = false

        const val EXTRA_SCRIPT_ID = "script_id"
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 1

        fun requestStop() { stopRequested = true }
        fun requestPause() { pauseRequested = true }
        fun requestResume() { pauseRequested = false }
        fun isRunning() = instance != null
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance != null) return START_NOT_STICKY // already running one workout
        val id = intent?.getLongExtra(EXTRA_SCRIPT_ID, -1) ?: -1
        val script = ScriptStore.get(this, id)
        if (script == null) { stopSelf(); return START_NOT_STICKY }

        instance = this
        stopRequested = false
        pauseRequested = false
        state.apply {
            phase = Phase.PREPARING; scriptName = script.name
            currentInstruction = ""; lastAnnouncement = ""; nextInstruction = ""
            remainingSec = 0.0; groupTotalSec = 0.0
            prepDone = 0; prepTotal = 0; prepFallback = 0
            warnings = emptyList(); error = ""
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Preparing…"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workoutinator:engine")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        thread(name = "workout-engine") { runWorkout(script) }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ engine

    private fun runWorkout(script: Script) {
        try {
            Tts.initSfx(this)
            val parsed = Parser.parse(script.content)
            state.warnings = parsed.warnings
            val events = parsed.events
            if (events.isEmpty()) { finish(Phase.ERROR, "Script has no playable lines"); return }

            // ---- prep: synthesize all phrases ----
            val phrases = events.filterIsInstance<Event.Speak>().map { it.text }
            state.prepTotal = phrases.distinct().size
            val failures = Tts.prepare(this, phrases) { done, total, fb ->
                state.prepDone = done; state.prepTotal = total; state.prepFallback = fb
                if (stopRequested) throw InterruptedException()
            }
            if (failures.isNotEmpty())
                state.warnings = state.warnings +
                        failures.map { "Could not synthesize: \"${it.take(40)}\" (will be skipped)" }

            // ---- run timeline ----
            val speaks = events.filterIsInstance<Event.Speak>().filter { !it.announcement }
            for ((idx, ev) in events.withIndex()) {
                if (stopRequested) break
                when (ev) {
                    is Event.Speak -> {
                        if (ev.announcement) state.lastAnnouncement = ev.text
                        else {
                            state.currentInstruction = ev.text
                            state.lastAnnouncement = ""
                            val si = speaks.indexOfFirst { it === ev }
                            state.nextInstruction = speaks.getOrNull(si + 1)?.text ?: ""
                        }
                        state.phase = Phase.SPEAKING
                        updateNotification(state.currentInstruction)
                        waitWhilePaused()
                        Tts.speakBlocking(this, ev.text) { stopRequested }
                    }
                    is Event.Wait -> runWait(ev)
                }
            }
            finish(if (stopRequested) Phase.IDLE else Phase.DONE, "")
        } catch (e: InterruptedException) {
            finish(Phase.IDLE, "")
        } catch (e: Exception) {
            finish(Phase.ERROR, e.message ?: "unknown error")
        }
    }

    private fun runWait(ev: Event.Wait) {
        state.phase = Phase.WAITING
        state.groupTotalSec = ev.groupTotal

        // rep beeps span [delayBeepTime, seconds), beepReps beeps at rep STARTS
        val beepTimes = if (ev.beepReps > 0)
            (0 until ev.beepReps).map { k ->
                ev.delayBeepTime + k * (ev.seconds - ev.delayBeepTime) / ev.beepReps
            } else emptyList()
        var beepIdx = 0

        var delayEndDone = ev.delayBeepTime <= 0
        if (!delayEndDone) Tts.beepDelayStart()

        var elapsed = 0.0
        var lastTick = SystemClock.elapsedRealtime()
        var lastNotifSec = -1
        while (elapsed < ev.seconds) {
            if (stopRequested) return
            if (pauseRequested) {
                state.phase = Phase.PAUSED
                updateNotification("Paused")
                while (pauseRequested && !stopRequested) Thread.sleep(100)
                state.phase = Phase.WAITING
                lastTick = SystemClock.elapsedRealtime()
            }
            Thread.sleep(50)
            val now = SystemClock.elapsedRealtime()
            elapsed += (now - lastTick) / 1000.0
            lastTick = now

            if (!delayEndDone && elapsed >= ev.delayBeepTime) {
                Tts.beepDelayEnd(); delayEndDone = true
            }
            while (beepIdx < beepTimes.size && elapsed >= beepTimes[beepIdx]) {
                Tts.beepRep(); beepIdx++
            }

            val groupRemaining = ev.groupTotal - ev.groupElapsedBefore - elapsed
            state.remainingSec = if (groupRemaining > 0) groupRemaining else 0.0
            val rs = state.remainingSec.toInt()
            if (rs != lastNotifSec) {
                lastNotifSec = rs
                updateNotification("${state.currentInstruction}  •  ${fmt(rs)}")
            }
        }
    }

    private fun waitWhilePaused() {
        while (pauseRequested && !stopRequested) {
            state.phase = Phase.PAUSED
            Thread.sleep(100)
        }
    }

    private fun finish(phase: Phase, error: String) {
        state.phase = phase
        state.error = error
        state.remainingSec = 0.0
        cleanup()
    }

    private fun cleanup() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        instance = null
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    // -------------------------------------------------------------- notifications

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.scriptName.ifEmpty { "Workout" })
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun fmt(totalSec: Int): String = "%d:%02d".format(totalSec / 60, totalSec % 60)
}
