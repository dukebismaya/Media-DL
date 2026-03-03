package com.bismaya.mediadl

import android.app.Application
import android.util.Log
import com.bismaya.mediadl.ui.TorrentNotifier
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class MediaDLApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install crash logger FIRST so it catches any init failures
        CrashLogger.install(this)
        // Create notification channels before any service starts
        TorrentNotifier.getInstance(this).makeNotifyChans()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("MediaDLApp", "Failed to initialize youtubedl-android", e)
            CrashLogger.logException(this, "INIT", e)
        }
    }
}
