package dreamteam.app

import android.content.Context
import androidx.core.content.edit

/** DRE-235 — the breathing-screen sound toggle, persisted via SharedPreferences. */
internal class BreathingSettings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSoundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND, SOUND_ENABLED_DEFAULT)
    fun setSoundEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_SOUND, enabled) }

    companion object {
        private const val PREFS = "dreamteam_breathing"
        private const val KEY_SOUND = "sound_enabled"
        /** DRE-235 — pleasant cues ON by default (no credential/permission gate). */
        const val SOUND_ENABLED_DEFAULT = true
    }
}
