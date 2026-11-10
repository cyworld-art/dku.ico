package com.dku.malnote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dku.malnote.service.TranscriptionService
import com.dku.malnote.ui.MalNoteRoot
import com.dku.malnote.ui.theme.MalNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MalNoteTheme {
                MalNoteRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TranscriptionService.resumePending(this)
    }
}
