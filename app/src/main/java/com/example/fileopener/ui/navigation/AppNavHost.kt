package com.example.fileopener.ui.navigation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.fileopener.R
import com.example.fileopener.ui.home.HomeScreen
import com.example.fileopener.ui.incoming.IncomingEvent
import com.example.fileopener.ui.incoming.IncomingViewModel
import com.example.fileopener.ui.viewer.ViewerScreen
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

/** [uri] — content:// или file:// URI документа; Navigation сам кодирует его в строку маршрута. */
@Serializable
data class ViewerRoute(val uri: String)

@Composable
fun AppNavHost(incomingViewModel: IncomingViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val resources = LocalResources.current
    val isImporting by incomingViewModel.isImporting.collectAsStateWithLifecycle()

    LaunchedEffect(incomingViewModel) {
        incomingViewModel.events.collect { event ->
            val error = when (event) {
                is IncomingEvent.Open -> {
                    navController.navigate(ViewerRoute(event.uri)) { launchSingleTop = true }
                    null
                }
                is IncomingEvent.Unsupported -> event.fileName
                    ?.let { resources.getString(R.string.incoming_unsupported, it) }
                    ?: resources.getString(R.string.incoming_unsupported_unknown)
                IncomingEvent.Failed -> resources.getString(R.string.incoming_failed)
            }
            if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(onOpenDocument = { document -> navController.navigate(ViewerRoute(document.uri)) })
            }
            composable<ViewerRoute> {
                ViewerScreen(onBack = { navController.popBackStack() })
            }
        }
        if (isImporting) ImportingOverlay()
    }
}

/** Блокирует ввод, пока большое вложение копируется во внутреннее хранилище. */
@Composable
private fun ImportingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.incoming_opening), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}
