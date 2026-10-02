package com.mobilemcp.pro.voice

import android.content.Context

object VoicePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("prime_voice", Context.MODE_PRIVATE)
    fun language(context: Context): String = prefs(context).getString("language", "fa-IR") ?: "fa-IR"
    fun setLanguage(context: Context, value: String) = prefs(context).edit().putString("language", value).apply()
    fun speed(context: Context): Float = prefs(context).getFloat("speed", 1f).coerceIn(0.75f, 1.3f)
    fun setSpeed(context: Context, value: Float) = prefs(context).edit().putFloat("speed", value.coerceIn(0.75f, 1.3f)).apply()
}
