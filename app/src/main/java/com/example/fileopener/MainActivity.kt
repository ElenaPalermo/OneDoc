package com.example.fileopener

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.IntentCompat
import com.example.fileopener.data.settings.AppLanguageStore
import com.example.fileopener.ui.common.LocalPdfThumbnails
import com.example.fileopener.ui.incoming.IncomingViewModel
import com.example.fileopener.ui.navigation.AppNavHost
import com.example.fileopener.ui.theme.FileOpenerTheme

class MainActivity : ComponentActivity() {
    private val incomingViewModel: IncomingViewModel by viewModels { IncomingViewModel.Factory }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // После поворота экрана Intent тот же — повторно не импортируем.
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            FileOpenerTheme {
                CompositionLocalProvider(LocalPdfThumbnails provides (application as DocFlowApp).container.pdfThumbnails) {
                    AppNavHost(incomingViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        incomingViewModel.open(uri.toString(), intent.type)
    }
}
