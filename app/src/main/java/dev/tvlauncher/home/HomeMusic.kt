package dev.tvlauncher.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Soft ambient loop for the home screen.
 * The loop is stored as raw 16 bit stereo PCM (res/raw/stardrift_loop.pcm, 14.7 kHz) and fed to an
 * AudioTrack sample by sample, so the end joins the start with no gap (a compressed file played by
 * MediaPlayer can leave a tiny gap). It fades in and out, and does not start when other audio is playing.
 */
object HomeMusic {
    private const val RATE = 14_700
    private const val FADE_IN_S = 4f
    private const val FADE_OUT_S = 1.5f

    private class Player(val ctx: Context) : Thread("stardrift-music") {
        @Volatile var want = true
        @Volatile var done = false

        override fun run() {
            var track: AudioTrack? = null
            try {
                val bytes = ctx.resources.openRawResource(R.raw.stardrift_loop).use { it.readBytes() }
                val pcm = ShortArray(bytes.size / 4 * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                val frames = pcm.size / 2
                if (frames < RATE || !want) return
                val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(Math.max(minBuf, RATE * 4 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.setVolume(level)
                t.play()
                val chunk = 1024
                val buf = ShortArray(chunk * 2)
                var pos = 0
                var gain = 0f
                while (true) {
                    val up = want
                    val step = chunk / (RATE * (if (up) FADE_IN_S else FADE_OUT_S))
                    val g0 = gain
                    gain = if (up) Math.min(1f, g0 + step) else Math.max(0f, g0 - step)
                    val g1 = gain
                    for (i in 0 until chunk) {
                        val g = g0 + (g1 - g0) * i / chunk
                        val gg = g * g
                        val p = (pos + i) % frames
                        buf[2 * i] = (pcm[2 * p] * gg).toInt().toShort()
                        buf[2 * i + 1] = (pcm[2 * p + 1] * gg).toInt().toShort()
                    }
                    pos = (pos + chunk) % frames
                    t.setVolume(level)
                    if (t.write(buf, 0, buf.size) < 0) break
                    if (!up && g1 <= 0f) break
                }
                t.stop()
            } catch (e: Throwable) {
                // no music is better than a crash on the home screen
            } finally {
                try { track?.release() } catch (e: Throwable) { }
                done = true
            }
        }
    }

    private var player: Player? = null
    @Volatile private var level = 0.6f
    // who wants the music right now ("home" screen, "dream" screensaver); it stops only when nobody does,
    // so waking the screensaver back into the launcher keeps it playing whichever order the two events arrive in
    private val owners = HashSet<String>()

    /** Volume from 0 to 1, applied at once to a running loop. */
    fun setLevel(v: Float) { level = v.coerceIn(0f, 1f) }

    /** Starts the loop. Stays silent when something else is already playing audio. */
    @Synchronized
    fun start(ctx: Context, owner: String = "home") {
        owners.add(owner)
        graceHandler.removeCallbacks(graceStop)
        val p = player
        if (p != null && !p.done) { p.want = true; return }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) return
        val np = Player(ctx.applicationContext)
        np.isDaemon = true
        player = np
        np.start()
    }

    /** Fades out and releases the audio once no owner wants it any more. */
    @Synchronized
    fun stop(owner: String = "home") {
        owners.remove(owner)
        if (owners.isEmpty()) {
            // short grace: when the screensaver hands over to the launcher (or back) the next owner starts a moment
            // later, and the same loop simply carries on from where it was instead of fading out and restarting
            graceHandler.removeCallbacks(graceStop)
            graceHandler.postDelayed(graceStop, 1200L)
        }
    }

    private val graceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val graceStop = Runnable { synchronized(this) { if (owners.isEmpty()) player?.want = false } }
}
