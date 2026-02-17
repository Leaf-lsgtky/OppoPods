package moe.chenxy.oppopods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

@SuppressLint("StaticFieldLeak")
object MediaControl {
    var mContext: Context? = null
    private val audioManager: AudioManager? by lazy {
        mContext?.getSystemService(AudioManager::class.java)
    }

    val isPlaying: Boolean?
        get() = audioManager?.isMusicActive

    @Synchronized
    fun sendPlay() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    @Synchronized
    fun sendPause() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    @Synchronized
    fun sendPlayPause() {
        if (isPlaying == true) {
            sendPause()
        } else {
            sendPlay()
        }
    }

    private fun sendKey(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        audioManager?.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        audioManager?.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }
}
