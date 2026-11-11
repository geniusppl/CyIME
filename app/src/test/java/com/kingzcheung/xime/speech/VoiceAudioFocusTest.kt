package com.kingzcheung.xime.speech

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.kingzcheung.xime.settings.SettingsPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用户 11-04：屏蔽外放音（压低音量 / 暂停播放 / 不屏蔽），默认不屏蔽；关麦恢复。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceAudioFocusTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val audio = mock<AudioManager>()
    private val context: Context get() = object : ContextWrapper(app) {
        override fun getSystemService(name: String): Any? = if (name == Context.AUDIO_SERVICE) audio else super.getSystemService(name)
    }

    @Before fun clear() {
        SettingsPreferences.getPrefsPublic(app).edit().clear().commit()
        whenever(audio.isMusicActive).thenReturn(true)
    }

    private fun focusAnswers(result: Int) =
        whenever(audio.requestAudioFocus(any<AudioFocusRequest>())).thenReturn(result)

    @Test fun defaultIsOffAndTouchesNothing() {
        assertEquals(VoiceOtherAudio.OFF, SettingsPreferences.getSttOtherAudio(app))
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        focus.release(owner)
        verify(audio, never()).requestAudioFocus(any<AudioFocusRequest>())
        verify(audio, never()).adjustStreamVolume(any(), any(), any())
        verify(audio, never()).setStreamVolume(any(), any(), any())
    }

    @Test fun pauseAsksForExclusiveFocusAndGivesItBack() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.PAUSE)
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        val request = argumentCaptor<AudioFocusRequest>()
        verify(audio).requestAudioFocus(request.capture())
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, request.firstValue.focusGain)
        verify(audio, never()).abandonAudioFocusRequest(any())
        focus.release(owner)
        verify(audio).abandonAudioFocusRequest(request.firstValue)
        focus.release(owner)
        verify(audio, times(1)).abandonAudioFocusRequest(any())
        verify(audio, never()).adjustStreamVolume(any(), any(), any())
        verify(audio, never()).setStreamVolume(any(), any(), any())
    }

    @Test fun overlappingSessionsRestoreAfterTheLastOne() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.PAUSE)
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val focus = VoiceAudioFocus(context)
        val old = Any()
        val new = Any()
        focus.acquire(old)
        focus.acquire(new)
        focus.release(old)
        verify(audio, times(1)).requestAudioFocus(any<AudioFocusRequest>())
        verify(audio, never()).abandonAudioFocusRequest(any())
        focus.release(new)
        verify(audio, times(1)).abandonAudioFocusRequest(any())
    }

    @Test fun refusedPauseMutesMediaAndRestoresIt() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.PAUSE)
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        verify(audio).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        assertTrue(SettingsPreferences.isSttMusicMutedByVoice(app))
        focus.release(owner)
        verify(audio).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        assertFalse(SettingsPreferences.isSttMusicMutedByVoice(app))
    }

    /** 用户 11-06：靠系统压低（可压低焦点）实测没用，压低音量改为直接调低媒体音量，不再申请焦点。 */
    @Test fun duckLowersMediaVolumeItselfAndRestoresIt() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.DUCK)
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        verify(audio).setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        assertEquals(10, SettingsPreferences.getSttMusicVolumeBeforeVoice(app))
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(2)
        focus.release(owner)
        verify(audio).setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        assertEquals(-1, SettingsPreferences.getSttMusicVolumeBeforeVoice(app))
        verify(audio, never()).requestAudioFocus(any<AudioFocusRequest>())
        verify(audio, never()).abandonAudioFocusRequest(any())
        verify(audio, never()).adjustStreamVolume(any(), any(), any())
    }

    @Test fun duckLeavesMutedOrFixedVolumeAlone() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.DUCK)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        whenever(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenReturn(true)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        focus.release(owner)
        whenever(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenReturn(false)
        whenever(audio.isVolumeFixed).thenReturn(true)
        focus.acquire(owner)
        focus.release(owner)
        verify(audio, never()).setStreamVolume(any(), any(), any())
    }

    /** learn-33：用户说话时把音量调到比原来还高才听用户的；挪了档但没高过原来，一律还原。 */
    @Test fun volumeTheUserRaisedAboveTheOriginalIsKept() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.DUCK)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(12)
        focus.release(owner)
        verify(audio, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(10), any())
    }

    @Test fun volumeMovedButNotAboveTheOriginalIsRestored() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.DUCK)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(5)
        focus.release(owner)
        verify(audio).setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
    }

    /** learn-33：持有者没放手（漏了一条结束路径）时，语音结束的 releaseAll 也一定还原。 */
    @Test fun releaseAllRestoresEvenWithALeakedOwnerAndIsIdempotent() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.DUCK)
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        val focus = VoiceAudioFocus(context)
        focus.acquire(Any())
        whenever(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(2)
        focus.releaseAll()
        focus.releaseAll()
        verify(audio, times(1)).setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        assertEquals(-1, SettingsPreferences.getSttMusicVolumeBeforeVoice(app))
    }

    @Test fun alreadyMutedMediaIsLeftAlone() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.PAUSE)
        whenever(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenReturn(true)
        focusAnswers(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        focus.release(owner)
        verify(audio, never()).adjustStreamVolume(any(), any(), any())
        verify(audio, never()).setStreamVolume(any(), any(), any())
    }

    @Test fun nothingPlayingTouchesNothing() {
        SettingsPreferences.setSttOtherAudio(app, VoiceOtherAudio.PAUSE)
        whenever(audio.isMusicActive).thenReturn(false)
        val focus = VoiceAudioFocus(context)
        val owner = Any()
        focus.acquire(owner)
        focus.release(owner)
        verify(audio, never()).requestAudioFocus(any<AudioFocusRequest>())
    }

    @Test fun volumeLeftByAKilledProcessIsRestoredOnNextStart() {
        SettingsPreferences.setSttMusicMutedByVoice(app, true)
        SettingsPreferences.setSttMusicVolumeBeforeVoice(app, 9)
        VoiceAudioFocus(context)
        verify(audio).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        verify(audio).setStreamVolume(AudioManager.STREAM_MUSIC, 9, 0)
        assertFalse(SettingsPreferences.isSttMusicMutedByVoice(app))
        assertEquals(-1, SettingsPreferences.getSttMusicVolumeBeforeVoice(app))
    }

    @Test fun unknownStoredValueFallsBackToOff() {
        SettingsPreferences.getPrefsPublic(app).edit().putString(SettingsPreferences.KEY_STT_OTHER_AUDIO, "x").commit()
        assertEquals(VoiceOtherAudio.OFF, SettingsPreferences.getSttOtherAudio(app))
    }
}
