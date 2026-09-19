package com.screenpulse.security

import android.content.Context
import org.json.JSONArray

/**
 * Synchronous store of "private" video keys (displayPath). Uses SharedPreferences so it can be
 * read during the (non-suspend) video list scan. This is a soft-hide / visibility flag, not
 * file encryption; combine it with the app lock for real protection.
 */
object PrivacyStore {

    private const val PREFS = "screen_pulse_privacy"
    private const val KEY_PRIVATE = "private_paths"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSet(context: Context): MutableSet<String> {
        val raw = prefs(context).getString(KEY_PRIVATE, null) ?: return linkedSetOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toCollection(linkedSetOf())
        }.getOrDefault(linkedSetOf())
    }

    private fun writeSet(context: Context, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_PRIVATE, arr.toString()).apply()
    }

    fun isPrivate(context: Context, key: String): Boolean = readSet(context).contains(key)

    fun privateCount(context: Context): Int = readSet(context).size

    fun setPrivate(context: Context, key: String, value: Boolean) {
        val set = readSet(context)
        val changed = if (value) set.add(key) else set.remove(key)
        if (changed) writeSet(context, set)
    }

    fun remove(context: Context, key: String) {
        val set = readSet(context)
        if (set.remove(key)) writeSet(context, set)
    }

    fun rename(context: Context, oldKey: String, newKey: String) {
        val set = readSet(context)
        if (set.remove(oldKey)) {
            set.add(newKey)
            writeSet(context, set)
        }
    }
}
