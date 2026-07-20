package com.example.ideavault

import android.content.Context

class AppLockPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun timeoutMillis(): Long = preferences
        .getLong(KEY_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS)
        .takeIf(ALLOWED_TIMEOUTS::contains)
        ?: DEFAULT_TIMEOUT_MILLIS

    fun setTimeoutMillis(timeoutMillis: Long) {
        require(timeoutMillis in ALLOWED_TIMEOUTS) { "Unsupported app lock timeout" }
        preferences.edit().putLong(KEY_TIMEOUT_MILLIS, timeoutMillis).apply()
    }

    companion object {
        const val TIMEOUT_IMMEDIATELY = 0L
        const val TIMEOUT_ONE_MINUTE = 60_000L
        const val TIMEOUT_FIVE_MINUTES = 5 * 60_000L
        const val TIMEOUT_FIFTEEN_MINUTES = 15 * 60_000L
        const val TIMEOUT_THIRTY_MINUTES = 30 * 60_000L
        const val TIMEOUT_ONE_HOUR = 60 * 60_000L
        const val TIMEOUT_FOUR_HOURS = 4 * 60 * 60_000L
        const val DEFAULT_TIMEOUT_MILLIS = TIMEOUT_FIVE_MINUTES

        val ALLOWED_TIMEOUTS = setOf(
            TIMEOUT_IMMEDIATELY,
            TIMEOUT_ONE_MINUTE,
            TIMEOUT_FIVE_MINUTES,
            TIMEOUT_FIFTEEN_MINUTES,
            TIMEOUT_THIRTY_MINUTES,
            TIMEOUT_ONE_HOUR,
            TIMEOUT_FOUR_HOURS,
        )

        private const val PREFERENCES_NAME = "app_lock_preferences"
        private const val KEY_TIMEOUT_MILLIS = "session_timeout_millis"
    }
}
