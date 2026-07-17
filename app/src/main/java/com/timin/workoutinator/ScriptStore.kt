package com.timin.workoutinator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Script(val id: Long, val name: String, val content: String)

object ScriptStore {
    private const val PREFS = "scripts"
    private const val KEY = "list"

    fun all(ctx: Context): List<Script> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Script(o.getLong("id"), o.getString("name"), o.getString("content"))
        }
    }

    fun get(ctx: Context, id: Long): Script? = all(ctx).firstOrNull { it.id == id }

    fun save(ctx: Context, script: Script) {
        val list = all(ctx).filter { it.id != script.id } + script
        write(ctx, list.sortedBy { it.name.lowercase() })
    }

    fun delete(ctx: Context, id: Long) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, list: List<Script>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("content", it.content))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
