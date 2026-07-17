package com.timin.workoutinator

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class EditorActivity : Activity() {

    private var editId: Long = -1
    private lateinit var nameInput: EditText
    private lateinit var contentInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editId = intent.getLongExtra("id", -1)
        val existing = if (editId >= 0) ScriptStore.get(this, editId) else null

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply { text = "Name:" })
        nameInput = EditText(this).apply {
            hint = "e.g. Core 10 min"
            setText(existing?.name ?: "")
        }
        root.addView(nameInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Script (instruction line, then M:SS[, keywords] line):"
        })
        contentInput = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = android.view.Gravity.TOP
            hint = "Lets get started.\n0:02\nplank for one minute\n1:00, countdown"
            setText(existing?.content ?: "")
            setHorizontallyScrolling(false)
        }
        root.addView(contentInput, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { trySave() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
    }

    private fun trySave() {
        val name = nameInput.text.toString().trim()
        val content = contentInput.text.toString()
        if (name.isEmpty()) { nameInput.error = "Name required"; return }
        if (content.isBlank()) { contentInput.error = "Script is empty"; return }

        val parsed = Parser.parse(content)
        val problems = parsed.warnings
        if (parsed.events.none { it is Event.Speak }) {
            AlertDialog.Builder(this)
                .setTitle("Nothing to play")
                .setMessage("No valid instruction lines found.\n\n" + problems.joinToString("\n"))
                .setPositiveButton("OK", null).show()
            return
        }
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Warnings")
                .setMessage(problems.joinToString("\n\n"))
                .setPositiveButton("Save anyway") { _, _ -> save(name, content) }
                .setNegativeButton("Keep editing", null)
                .show()
        } else save(name, content)
    }

    private fun save(name: String, content: String) {
        val id = if (editId >= 0) editId else System.currentTimeMillis()
        ScriptStore.save(this, Script(id, name, content))
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
