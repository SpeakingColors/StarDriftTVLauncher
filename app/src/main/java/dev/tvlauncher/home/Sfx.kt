package dev.tvlauncher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Soft interface blips (the approved "v6" set: F major, 50% reverb). Played with a SoundPool so they start at once.
 * Volume and on/off come from the prefs "fx" and "fx_vol" (percent), set in the speaker popup.
 */
object Sfx {
    const val MOVE_H = 0
    const val MOVE_V = 1
    const val SELECT = 2
    const val BACK = 3
    const val OPEN = 4
    const val CLOSE = 5
    const val BUMP = 6
    const val ON = 7
    const val OFF = 8

    private val RES = intArrayOf(R.raw.sfx_move_h, R.raw.sfx_move_v, R.raw.sfx_select, R.raw.sfx_back, R.raw.sfx_open,
        R.raw.sfx_close, R.raw.sfx_bump, R.raw.sfx_on, R.raw.sfx_off)

    private var pool: SoundPool? = null
    private val ids = IntArray(RES.size)
    private val ready = BooleanArray(RES.size)
    @Volatile var enabled = true
    @Volatile var level = 0.45f
    private var lastAt = 0L

    @Synchronized
    fun init(ctx: Context) {
        if (pool != null) return
        try {
            val p = SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build()
            p.setOnLoadCompleteListener { _, sid, status ->
                if (status == 0) for (i in ids.indices) if (ids[i] == sid) ready[i] = true
            }
            val app = ctx.applicationContext
            for (i in RES.indices) ids[i] = p.load(app, RES[i], 1)
            pool = p
        } catch (e: Throwable) {
            pool = null
        }
    }

    fun load(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean("fx", true)
        level = prefs.getInt("fx_vol", 45) / 100f
    }

    /** Plays one blip. Two blips closer than 35 ms apart are merged so a held key does not stack sounds. */
    fun play(which: Int) {
        if (!enabled || level <= 0f) return
        val p = pool ?: return
        if (which !in ids.indices || !ready[which]) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < 35L) return
        lastAt = now
        val v = level
        try { p.play(ids[which], v, v, 1, 0, 1f) } catch (e: Throwable) { }
    }
}
