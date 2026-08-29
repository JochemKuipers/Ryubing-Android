package org.ryubing.android.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONObject

class AppLifecycleStore(context: Context) {
    private val prefs = context.getSharedPreferences("ryubing_lifecycle", Context.MODE_PRIVATE)

    // Every write below uses commit(): callers kill the process immediately afterwards,
    // and apply()'s background flush would be lost.
    fun queueLaunch(game: GameEntry) {
        prefs.edit(commit = true) {
            putString(
                KEY_PENDING_GAME,
                JSONObject()
                    .put("title", game.title)
                    .put("uri", game.uri.toString())
                    .put("size", game.sizeBytes)
                    .put("title_id", game.titleId)
                    .put("version", game.version)
                    .put("file_name", game.fileName)
                    .toString(),
            )
            putBoolean(KEY_INTENTIONAL_RESTART, true)
        }
    }

    fun consumePendingLaunch(): GameEntry? {
        val raw = prefs.getString(KEY_PENDING_GAME, null) ?: return null
        prefs.edit(commit = true) { remove(KEY_PENDING_GAME); putBoolean(KEY_INTENTIONAL_RESTART, false) }
        return runCatching {
            val json = JSONObject(raw)
            GameEntry(
                title = json.getString("title"),
                uri = Uri.parse(json.getString("uri")),
                sizeBytes = json.optLong("size"),
                titleId = json.optString("title_id"),
                version = json.optString("version"),
                fileName = json.optString("file_name"),
            )
        }.getOrNull()
    }

    fun markSessionStarted(systemDriver: Boolean) {
        prefs.edit(commit = true) {
            putBoolean(KEY_SESSION_ACTIVE, true)
            putBoolean(KEY_SESSION_USED_SYSTEM_DRIVER, systemDriver)
            putBoolean(KEY_INTENTIONAL_RESTART, false)
        }
    }

    fun markSessionStopped() {
        prefs.edit(commit = true) { putBoolean(KEY_SESSION_ACTIVE, false) }
    }

    fun markIntentionalRestart() {
        prefs.edit(commit = true) { putBoolean(KEY_INTENTIONAL_RESTART, true) }
    }

    fun consumeSystemDriverCrash(): Boolean {
        val crashed = prefs.getBoolean(KEY_SESSION_ACTIVE, false) &&
            prefs.getBoolean(KEY_SESSION_USED_SYSTEM_DRIVER, false) &&
            !prefs.getBoolean(KEY_INTENTIONAL_RESTART, false)
        prefs.edit(commit = true) {
            putBoolean(KEY_SESSION_ACTIVE, false)
            putBoolean(KEY_INTENTIONAL_RESTART, false)
        }
        return crashed
    }

    private companion object {
        const val KEY_PENDING_GAME = "pending_game"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_SESSION_USED_SYSTEM_DRIVER = "session_used_system_driver"
        const val KEY_INTENTIONAL_RESTART = "intentional_restart"
    }
}