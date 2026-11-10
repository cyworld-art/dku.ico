package com.dku.malnote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dku.malnote.data.ModelManager
import com.dku.malnote.data.RecordingRepository
import com.dku.malnote.data.SettingsStore

class MalNoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        RecordingRepository.init(this)
        ModelManager.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORD, "녹음", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRANSCRIBE, "음성 전사", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CH_RECORD = "record"
        const val CH_TRANSCRIBE = "transcribe"
    }
}
