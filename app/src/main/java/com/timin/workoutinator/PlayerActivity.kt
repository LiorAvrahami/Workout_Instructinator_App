package com.timin.workoutinator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.timin.workoutinator.WorkoutService.Phase
import kotlin.math.abs

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
    private var sawActive = false   // this activity has seen its own run's active phase
    private var finishing = false
    private val createdAt = android.os.SystemClock.elapsedRealtime()

    // Stale DONE/IDLE from a previous run can linger in the static state for a
    // moment before the new service run resets it; give it a grace window.
    private fun gracePassed() =
        android.os.SystemClock.elapsedRealtime() - createdAt > 3000

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
                WorkoutService.requestStop()
                finish() // straight back to the script list
            }
        }
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "swipe ←  skip wait      swipe →  replay / previous"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7F94"))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(80) && abs(dx) > 1.5f * abs(dy) && abs(vx) > 500) {
                    if (!WorkoutService.isRunning()) return false
                    if (dx < 0) {
                        WorkoutService.requestSkipNext()
                        Toast.makeText(this@PlayerActivity, "⏩ next", Toast.LENGTH_SHORT).show()
                    } else {
                        WorkoutService.requestBack()
                        Toast.makeText(this@PlayerActivity, "⏪ back", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val poll = object : Runnable {
        override fun run() {
            val s = WorkoutService.state
            titleView.text = s.scriptName
            when (s.phase) {
                Phase.PREPARING -> {
                    sawActive = true
                    pauseButton.isEnabled = true
                    stopButton.text = "■ Stop"
                    phaseView.text = "Preparing audio  ${s.prepDone}/${s.prepTotal}" +
                            if (s.prepFallback > 0) "  (${s.prepFallback} via device TTS)" else ""
                    timerView.text = "…"
                    instructionView.text = "Synthesizing phrases.\nNeeds internet on first run;\ncached afterwards."
                }
                Phase.SPEAKING, Phase.WAITING, Phase.PAUSED -> {
                    sawActive = true
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
                    if (sawActive && !finishing) {
                        finishing = true
                        phaseView.text = ""
                        timerView.text = "✓"
                        instructionView.text = "Workout complete"
                        announcementView.text = ""
                        nextView.text = ""
                        handler.postDelayed({ finish() }, 1200)
                    } else if (!sawActive && gracePassed()) finish() // stale state from a past run
                }
                Phase.ERROR -> {
                    phaseView.text = "error"
                    instructionView.text = s.error
                    stopButton.text = "Close" // error stays readable; Stop/Close exits
                }
                Phase.IDLE -> {
                    if (!WorkoutService.isRunning() && !finishing &&
                        (sawActive || gracePassed())
                    ) {
                        finishing = true
                        finish()
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
