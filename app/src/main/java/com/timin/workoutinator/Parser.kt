package com.timin.workoutinator

/**
 * Kotlin port of read_instructions_file.py, minus Sets (@), preferences ($),
 * and until_end_of_song (no music in this app).
 *
 * Output is a flat event list:
 *   Speak(text, announcement)  — announcement=true for inserted "for X"/countdown phrases
 *   Wait(seconds, delayBeepTime, beepReps, groupTotal, groupElapsedBefore)
 *
 * Countdown waits are pre-expanded into (Wait, Speak) chains exactly like
 * apply_preliminary_keywords(), including the word-count time compensation.
 * groupTotal/groupElapsedBefore let the UI show remaining time of the ORIGINAL
 * wait rather than of each expanded chunk.
 */
sealed class Event {
    data class Speak(val text: String, val announcement: Boolean) : Event()
    data class Wait(
        val seconds: Double,
        val delayBeepTime: Double,
        val beepReps: Int,
        val groupTotal: Double,
        val groupElapsedBefore: Double
    ) : Event()
}

object Parser {

    data class Result(val events: List<Event>, val warnings: List<String>)

    // ---- intermediate line model, mirroring TextLine / WaitLine ----
    private class TL(var text: String)
    private class WL {
        var time = 0.0
        var countdown = false
        var beepReps = 0
        var announceTime = false
        var delayBeep = false
        var delayBeepTime = 0.0
        fun isEmpty() = time == 0.0
    }

    @Synchronized
    fun parse(raw: String): Result {
        val warnings = mutableListOf<String>()
        val lines = raw.replace("\r", "").split("\n")

        // ---- pass 1: alternate text/wait, skipping #, empty, @, $ ----
        val list = mutableListOf<Any>()
        var isTextLine = true
        for ((lineIndex, line0) in lines.withIndex()) {
            val line = line0
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("@") || line.startsWith("$")) {
                warnings.add("Line ${lineIndex + 1} skipped (Sets/preferences not supported): $line")
                continue
            }
            try {
                if (isTextLine) {
                    list.add(TL(line))
                } else {
                    val wl = WL()
                    val args = line.replace(" ", "").split(",")
                    val time = args[0].split(":")
                    require(time.size == 2) { "time must be M:SS" }
                    wl.time = time[0].toDouble() * 60 + time[1].toDouble()
                    val kw = args.drop(1)
                    wl.countdown = kw.contains("countdown")
                    wl.announceTime = kw.any { it.contains("announce_time") }
                    if (kw.contains("until_end_of_song"))
                        warnings.add("Line ${lineIndex + 1}: until_end_of_song ignored (no in-app music)")
                    val di = kw.indexOf("delaybeep")
                    if (di >= 0) {
                        wl.delayBeep = true
                        wl.delayBeepTime = kw.getOrNull(di + 1)?.toDoubleOrNull()
                            ?: throw IllegalArgumentException("delaybeep needs a number after it")
                    }
                    if (kw.contains("beepreps")) {
                        wl.beepReps = extractReps(list.size - 1, list)
                            ?: throw IllegalArgumentException("beepreps: no number found in previous instruction")
                    }
                    list.add(wl)
                }
                isTextLine = !isTextLine
            } catch (e: Exception) {
                warnings.add("Problem with line ${lineIndex + 1} (\"$line\"): ${e.message}. Line skipped.")
                // keep alternation as python does on failure? python raises; we skip the line
                // without toggling, so the next line is still expected to be the same kind.
            }
        }

        // ---- pass 2: apply_preliminary_keywords (announce_time + countdown expansion) ----
        var i = -1
        while (true) {
            i += 1
            if (i >= list.size) break
            val line = list[i] as? WL ?: continue
            val originalTime = line.time.toInt()

            if (line.announceTime || line.countdown) {
                val ann = when {
                    originalTime < 60 -> "for $originalTime seconds"
                    originalTime % 60 != 0 -> "for ${originalTime / 60} minutes ${originalTime % 60} seconds"
                    else -> "for ${originalTime / 60} minutes"
                }
                list.add(i, TL(ann))
                i += 1
                line.announceTime = false
            }

            if (line.countdown) {
                list.removeAt(i)
                val times = mutableListOf<Int>()
                val comments = mutableListOf<String>()
                var timeLeft = originalTime
                while (timeLeft != 0) {
                    if (timeLeft >= 3 * 60) {
                        times.add(60 + timeLeft % 60)
                        timeLeft -= times.last()
                        comments.add("${timeLeft / 60} minutes")
                    }
                    if (timeLeft >= 90) {
                        times.add(timeLeft - 60); timeLeft -= times.last(); comments.add("1 minute")
                    } else if (timeLeft >= 45) {
                        times.add(timeLeft - 30); timeLeft -= times.last(); comments.add("30 seconds")
                    } else if (timeLeft >= 30) {
                        times.add(timeLeft - 10); timeLeft -= times.last(); comments.add("10 seconds")
                    } else {
                        times.add(timeLeft); timeLeft -= times.last()
                    }
                }
                // subtract expected announcement durations (1s per word) from following waits
                val annDur = comments.map { it.split(" ").size }
                val adj = listOf(times[0]) + (1 until times.size).map { k -> times[k] - annDur[k - 1] }

                var elapsedBefore = 0.0
                for (k in adj.indices) {
                    val w = WL()
                    w.time = adj[k].toDouble()
                    if (k == 0) { w.delayBeep = line.delayBeep; w.delayBeepTime = line.delayBeepTime }
                    // stash group info piggybacked via negative marker? cleaner: wrap later.
                    groupInfo[w] = Pair(originalTime.toDouble(), elapsedBefore)
                    elapsedBefore += times[k]  // display uses UNadjusted chunk lengths
                    list.add(i + k * 2, w)
                    if (k < comments.size) list.add(i + k * 2 + 1, TL(comments[k]).also { announcements.add(it) })
                }
            }
        }

        // ---- pass 3: drop empty waits, merge consecutive TextLines ----
        val cleaned = list.filter { it !is WL || !it.isEmpty() }.toMutableList()
        var j = 0
        while (j < cleaned.size - 1) {
            val a = cleaned[j]; val b = cleaned[j + 1]
            if (a is TL && b is TL) {
                a.text += " " + b.text
                // merged line is an announcement only if BOTH parts were
                if (!(announcements.contains(a) && announcements.contains(b)))
                    announcements.remove(a)
                cleaned.removeAt(j + 1)
            } else j += 1
        }

        // ---- emit events ----
        val events = cleaned.map {
            when (it) {
                is TL -> Event.Speak(it.text, announcements.contains(it))
                is WL -> {
                    val g = groupInfo[it]
                    Event.Wait(
                        it.time, it.delayBeepTime, it.beepReps,
                        g?.first ?: it.time, g?.second ?: 0.0
                    )
                }
                else -> throw IllegalStateException()
            }
        }
        announcements.clear(); groupInfo.clear()
        return Result(events, warnings)
    }

    private val announcements = mutableSetOf<TL>()
    private val groupInfo = mutableMapOf<WL, Pair<Double, Double>>()

    /** extract_number_of_repetitions with the change/switch two-back recursion */
    private fun extractReps(index: Int, list: List<Any>): Int? {
        val line = list.getOrNull(index) as? TL ?: return null
        val m = Regex("""\d+""").find(line.text)
        if (m != null) return m.value.toInt()
        val t = line.text.lowercase()
        return if (t.startsWith("change") || t.startsWith("switch"))
            extractReps(index - 2, list) else null
    }
}
