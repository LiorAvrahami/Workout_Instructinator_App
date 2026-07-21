package com.timin.workoutinator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
        /**
         * Swipe-left behavior: if more than this many seconds have passed since
         * the current instruction STARTED being spoken, replay it; otherwise
         * jump to the previous instruction. Tweak freely.
         */
        const val BACK_REPLAY_THRESHOLD_SECONDS = 2.0

        val state = State()
        @Volatile private var instance: WorkoutService? = null
        @Volatile private var stopRequested = false
        @Volatile private var pauseRequested = false

        const val EXTRA_SCRIPT_ID = "script_id"
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 1

        // notification button actions
        private const val ACTION_TOGGLE = "com.timin.workoutinator.TOGGLE"
        private const val ACTION_NEXT = "com.timin.workoutinator.NEXT"
        private const val ACTION_PREV = "com.timin.workoutinator.PREV"

        fun requestStop() { stopRequested = true }
        fun requestPause() { pauseRequested = true }
        fun requestResume() { pauseRequested = false }
        fun requestSkipNext() { instance?.skipNext() }
        fun requestBack() { instance?.back() }
        fun isRunning() = instance != null
    }

    // --- swipe navigation state ---
    @Volatile private var jumpTarget = -1        // event index to jump to; -1 = none
    @Volatile private var curInstrEventIdx = 0   // event index of current main instruction
    @Volatile private var anchorMs = 0L          // when that instruction started speaking
    @Volatile private var instrIdx: List<Int> = emptyList()
    @Volatile private var eventCount = 0

    private fun skipNext() {
        val cur = curInstrEventIdx
        jumpTarget = instrIdx.firstOrNull { it > cur } ?: eventCount // past end = finish
    }

    private fun back() {
        val cur = curInstrEventIdx
        val since = (SystemClock.elapsedRealtime() - anchorMs) / 1000.0
        jumpTarget = if (since > BACK_REPLAY_THRESHOLD_SECONDS) cur
        else instrIdx.lastOrNull { it < cur } ?: cur
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification button presses arrive here as action intents.
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (pauseRequested) requestResume() else requestPause()
                instance?.updateNotification(
                    if (pauseRequested) "Paused" else state.currentInstruction
                )
                return START_NOT_STICKY
            }
            ACTION_NEXT -> { instance?.skipNext(); return START_NOT_STICKY }
            ACTION_PREV -> { instance?.back(); return START_NOT_STICKY }
        }
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
            instrIdx = events.indices.filter {
                val e = events[it]; e is Event.Speak && !e.announcement
            }
            eventCount = events.size
            jumpTarget = -1

            var i = 0
            while (i < events.size) {
                if (stopRequested) break
                val jt = jumpTarget
                if (jt >= 0) {
                    jumpTarget = -1
                    i = jt
                    if (i >= events.size) break
                    continue
                }
                when (val ev = events[i]) {
                    is Event.Speak -> {
                        if (ev.announcement) state.lastAnnouncement = ev.text
                        else {
                            curInstrEventIdx = i
                            anchorMs = SystemClock.elapsedRealtime()
                            state.currentInstruction = ev.text
                            state.lastAnnouncement = ""
                            val si = instrIdx.indexOf(i)
                            state.nextInstruction = instrIdx.getOrNull(si + 1)
                                ?.let { (events[it] as Event.Speak).text } ?: ""
                        }
                        state.phase = Phase.SPEAKING
                        updateNotification(state.currentInstruction)
                        waitWhilePaused()
                        Tts.speakBlocking(this, ev.text) { stopRequested || jumpTarget >= 0 }
                        // Re-anchor the back-swipe timer so the replay-vs-previous
                        // threshold counts from when the instructor STOPS speaking.
                        if (!ev.announcement) anchorMs = SystemClock.elapsedRealtime()
                    }
                    is Event.Wait -> runWait(ev)
                }
                i++
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
            if (stopRequested || jumpTarget >= 0) return
            if (pauseRequested) {
                state.phase = Phase.PAUSED
                updateNotification("Paused")
                while (pauseRequested && !stopRequested && jumpTarget < 0) Thread.sleep(100)
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
        while (pauseRequested && !stopRequested && jumpTarget < 0) {
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

        fun actionPI(action: String, req: Int): PendingIntent = PendingIntent.getService(
            this, req,
            Intent(this, WorkoutService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

        fun act(iconRes: Int, title: String, action: String, req: Int): Notification.Action =
            Notification.Action.Builder(
                Icon.createWithResource(this, iconRes), title, actionPI(action, req)
            ).build()

        val paused = pauseRequested
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.scriptName.ifEmpty { "Workout" })
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV, 1))
            .addAction(
                if (paused) act(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE, 2)
                else act(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 2)
            )
            .addAction(act(android.R.drawable.ic_media_next, "Next", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun fmt(totalSec: Int): String = "%d:%02d".format(totalSec / 60, totalSec % 60)
}
