package com.sekiguchi.irogame

import android.app.Activity
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private var bgm: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(GameView(this))
    }

    override fun onResume() {
        super.onResume()
        if (bgm == null) {
            bgm = MediaPlayer.create(this, R.raw.bgm)
            bgm?.isLooping = true
            bgm?.setVolume(0.2f, 0.2f) // 小音
        }
        bgm?.start()
    }

    override fun onPause() {
        super.onPause()
        bgm?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgm?.release()
        bgm = null
    }
}
