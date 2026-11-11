package com.kingzcheung.xime.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.kingzcheung.xime.settings.SettingsPreferences

/** 屏蔽外放音的三种做法（用户 11-04 截图的选项和叫法）；默认不屏蔽，和以前一样（用户「默认不变」）。 */
enum class VoiceOtherAudio(val id: String, val label: String) {
    DUCK("duck", "压低音量"),
    PAUSE("pause", "暂停播放"),
    OFF("off", "不屏蔽");

    companion object {
        val DEFAULT = OFF
        fun fromId(id: String?): VoiceOtherAudio = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 屏蔽外放音（用户 2026-11-04 要的）：语音输入开麦时把正在放的音乐、视频压低或暂停，关麦就恢复。
 *
 * 暂停播放：申请临时独占音频焦点，播放器自己暂停、录完自己接着放。Android 15 起不在前台的应用申请焦点
 * 会被系统拒绝，输入法可能也算在内；这时退回为临时静音媒体音量。
 * 压低音量：直接把媒体音量临时调到原来的五分之一。原来用可压低的焦点让系统去压，用户 11-06 实测没用——
 * 播放器是语音内容（播客、视频解说）或用 AAudio 时系统不压，交给应用自己处理，很多应用收到也不理。
 *
 * 关麦时只还原自己动过的那次，说话时用户自己调过音量就听用户的。动音量之前先落盘记一笔，进程中途被杀
 * 没来得及还原，下次创建时补上，不会让媒体一直很小声或没声。
 *
 * 只在开麦那一刻有媒体在放时动作；同一时刻可能有新旧两段录音交接，按持有者计数，最后一个放手才恢复。
 */
internal class VoiceAudioFocus(private val context: Context) {
    companion object {
        private const val TAG = "VoiceAudioFocus"
        /** 压低音量时调到原音量的几分之一。 */
        private const val DUCK_DIVISOR = 5
    }

    private val lock = Any()
    private val owners = HashSet<Any>()
    private var focusRequest: AudioFocusRequest? = null
    private var mutedMusic = false
    /** 压低前的媒体音量和压到的音量；-1 表示没压。 */
    private var volumeBefore = -1
    private var volumeLowered = -1

    private val audio: AudioManager? by lazy {
        try { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager } catch (_: Exception) { null }
    }

    init {
        // 上次动了媒体音量后进程没走到恢复：先把声音还回去。
        try {
            if (SettingsPreferences.isSttMusicMutedByVoice(context)) {
                unmuteMusic()
                SettingsPreferences.setSttMusicMutedByVoice(context, false)
            }
            val before = SettingsPreferences.getSttMusicVolumeBeforeVoice(context)
            if (before >= 0) {
                audio?.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                SettingsPreferences.setSttMusicVolumeBeforeVoice(context, -1)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Restoring media volume failed", error)
        }
    }

    fun acquire(owner: Any) { synchronized(lock) {
        if (!owners.add(owner) || owners.size > 1) return
        try {
            val mode = SettingsPreferences.getSttOtherAudio(context)
            if (mode == VoiceOtherAudio.OFF) return
            val manager = audio ?: return
            if (!manager.isMusicActive) return
            if (mode == VoiceOtherAudio.DUCK) {
                lowerMusic(manager)
                return
            }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener { }
                .build()
            if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                return
            }
            // 系统不给焦点：临时静音媒体音量（不弹音量条），关麦时还原。
            if (manager.isVolumeFixed || manager.isStreamMute(AudioManager.STREAM_MUSIC)) return
            SettingsPreferences.setSttMusicMutedByVoice(context, true)
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            mutedMusic = true
            Log.i(TAG, "Audio focus refused; media muted while recording")
        } catch (error: Exception) {
            Log.w(TAG, "Quieting other audio failed", error)
        }
    } }

    /** 媒体音量临时调到原来的 1/[DUCK_DIVISOR]（不弹音量条）。 */
    private fun lowerMusic(manager: AudioManager) {
        if (manager.isVolumeFixed || manager.isStreamMute(AudioManager.STREAM_MUSIC)) return
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val lowered = current / DUCK_DIVISOR
        if (current <= 0 || lowered >= current) return
        SettingsPreferences.setSttMusicVolumeBeforeVoice(context, current)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, lowered, 0)
        volumeBefore = current
        volumeLowered = lowered
    }

    fun release(owner: Any) { synchronized(lock) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        restore()
    } }

    private fun restore() {
        val request = focusRequest
        focusRequest = null
        if (request != null) try { audio?.abandonAudioFocusRequest(request) } catch (error: Exception) {
            Log.w(TAG, "Abandoning audio focus failed", error)
        }
        if (mutedMusic) {
            mutedMusic = false
            try {
                unmuteMusic()
                SettingsPreferences.setSttMusicMutedByVoice(context, false)
            } catch (error: Exception) {
                Log.w(TAG, "Restoring media volume failed", error)
            }
        }
        if (volumeBefore >= 0) {
            val before = volumeBefore
            val lowered = volumeLowered
            volumeBefore = -1
            volumeLowered = -1
            try {
                // 说话时用户自己把音量调高了就听用户的；否则一律还原。原来要求音量恰好等于压到的值，
                // 安卓 17 上系统会把音量档位挪一格（或换了耳机/外放），对不上就一直不还原，声音被留小（用户 11-11 反馈）。
                val manager = audio
                if (manager != null && manager.getStreamVolume(AudioManager.STREAM_MUSIC) <= lowered + 1) {
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
                }
                SettingsPreferences.setSttMusicVolumeBeforeVoice(context, -1)
            } catch (error: Exception) {
                Log.w(TAG, "Restoring media volume failed", error)
            }
        }
    }

    private fun unmuteMusic() {
        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }
}
