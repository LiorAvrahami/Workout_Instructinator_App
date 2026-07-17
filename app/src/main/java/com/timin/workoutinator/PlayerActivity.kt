package com.timin.workoutinator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.timin.workoutinator.WorkoutService.Phase

class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var phaseView: TextView
    private lateinit var instructionView: TextView
    private lateinit var announcementView: TextView
    private lateinit var timerView: TextView
    private lateinit var nextView: TextView
    private lateinit var warningsView: TextView
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getLongExtra("id", -1)
        if (id >= 0 && !WorkoutService.isRunning()) {
            startForegroundService(
                Intent(this, WorkoutService::class.java)
                    .putExtra(WorkoutService.EXTRA_SCRIPT_ID, id)
            )
        }

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0F1720"))
        }

        titleView = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#9FB3C8"))
        }
        root.addView(titleView)

        phaseView = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#6B7F94"))
        }
        root.addView(phaseView)

        timerView = TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(timerView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        instructionView = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val scroll = ScrollView(this).apply { addView(instructionView) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        announcementView = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#F5C518")); gravity = Gravity.CENTER
        }
        root.addView(announcementView)

        nextView = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#9FB3C8")); gravity = Gravity.CENTER
        }
        root.addView(nextView)

        warningsView = TextView(this).apply {
            textSize = 11f; setTextColor(Color.parseColor("#B0653A"))
        }
        root.addView(warningsView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pauseButton = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                paused = !paused
                if (paused) WorkoutService.requestPause() else WorkoutService.requestResume()
                text = if (paused) "Resume" else "Pause"
            }
        }
        buttons.addView(pauseButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        stopButton = Button(this).apply {
            text = "■ Stop"
            setOnClickListener {
                if (WorkoutService.isRunning()) WorkoutService.requestStop() else finish()
            }
        }
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = WorkoutService.state
            titleView.text = s.scriptName
            when (s.phase) {
                Phase.PREPARING -> {
                    pauseButton.isEnabled = true
                    stopButton.text = "■ Stop"
                    phaseView.text = "Preparing audio  ${s.prepDone}/${s.prepTotal}" +
                            if (s.prepFallback > 0) "  (${s.prepFallback} via device TTS)" else ""
                    timerView.text = "…"
                    instructionView.text = "Synthesizing phrases.\nNeeds internet on first run;\ncached afterwards."
                }
                Phase.SPEAKING, Phase.WAITING, Phase.PAUSED -> {
                    phaseView.text = when (s.phase) {
                        Phase.SPEAKING -> "speaking"
                        Phase.PAUSED -> "paused"
                        else -> "waiting"
                    }
                    timerView.text = fmt(s.remainingSec)
                    instructionView.text = s.currentInstruction
                    announcementView.text = s.lastAnnouncement
                    nextView.text =
                        if (s.nextInstruction.isNotEmpty()) "next: ${s.nextInstruction}" else ""
                }
                Phase.DONE -> {
                    phaseView.text = ""
                    timerView.text = "✓"
                    instructionView.text = "Workout complete"
                    announcementView.text = ""
                    nextView.text = ""
                    stopButton.text = "Close"
                    pauseButton.isEnabled = false
                }
                Phase.ERROR -> {
                    phaseView.text = "error"
                    instructionView.text = s.error
                    stopButton.text = "Close"
                    pauseButton.isEnabled = false
                }
                Phase.IDLE -> {
                    if (!WorkoutService.isRunning()) {
                        stopButton.text = "Close"
                        pauseButton.isEnabled = false
                        if (instructionView.text.isEmpty()) instructionView.text = "Stopped"
                    }
                }
            }
            warningsView.text = s.warnings.take(3).joinToString("\n")
            handler.postDelayed(this, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null) // workout keeps running in the service
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
