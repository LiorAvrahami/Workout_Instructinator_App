package com.timin.workoutinator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var listLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Workout Instructor-Inator"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        })

        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listLayout) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(Button(this).apply {
            text = "+  New script"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, EditorActivity::class.java))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        resumeButton = Button(this).apply {
            text = "▶ Return to running workout"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
            }
        }
        root.addView(resumeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private lateinit var resumeButton: Button

    override fun onResume() {
        super.onResume()
        resumeButton.visibility =
            if (WorkoutService.isRunning()) android.view.View.VISIBLE else android.view.View.GONE
        rebuildList()
    }

    private fun rebuildList() {
        listLayout.removeAllViews()
        val scripts = ScriptStore.all(this)
        if (scripts.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "\nNo scripts yet. Tap “New script” and paste a workout."
                gravity = Gravity.CENTER
            })
            return
        }
        for (s in scripts) listLayout.addView(card(s))
    }

    private fun card(s: Script): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E2A38"))
            setPadding(dp(16), dp(18), dp(8), dp(18))
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.topMargin = dp(10)
        outer.layoutParams = lp

        // The whole left area is the Play target — big and obvious.
        val playArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { play(s) }
        }
        playArea.addView(TextView(this).apply {
            text = "▶  ${s.name}"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        playArea.addView(TextView(this).apply {
            text = summarize(s)
            setTextColor(Color.parseColor("#9FB3C8"))
            textSize = 13f
        })
        outer.addView(playArea, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Small overflow menu; Delete lives only here, behind a confirm dialog.
        outer.addView(Button(this).apply {
            text = "⋮"
            setOnClickListener { v ->
                PopupMenu(this@MainActivity, v).apply {
                    menu.add("Edit")
                    menu.add("Delete")
                    setOnMenuItemClickListener {
                        when (it.title) {
                            "Edit" -> startActivity(
                                Intent(this@MainActivity, EditorActivity::class.java)
                                    .putExtra("id", s.id)
                            )
                            "Delete" -> confirmDelete(s)
                        }
                        true
                    }
                }.show()
            }
        }, LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))

        return outer
    }

    private fun confirmDelete(s: Script) {
        AlertDialog.Builder(this)
            .setTitle("Delete “${s.name}”?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ScriptStore.delete(this, s.id); rebuildList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun play(s: Script) {
        val i = Intent(this, PlayerActivity::class.java).putExtra("id", s.id)
        startActivity(i)
    }

    private fun summarize(s: Script): String {
        return try {
            val ev = Parser.parse(s.content).events
            val waitSec = ev.filterIsInstance<Event.Wait>().sumOf { it.seconds }
            val speaks = ev.count { it is Event.Speak }
            val total = waitSec + speaks * 2 // rough 2s per utterance
            "~${(total / 60).toInt()} min • $speaks phrases"
        } catch (e: Exception) {
            "(parse error)"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
