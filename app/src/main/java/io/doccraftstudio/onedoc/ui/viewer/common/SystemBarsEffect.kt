package io.doccraftstudio.onedoc.ui.viewer.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive-режим и цвет значков системных панелей на экране чтения.
 * [immersive] прячет статус-бар и навигацию (возвращаются свайпом от края),
 * [lightBars] — тёмные значки для светлых тем. При уходе с экрана всё восстанавливается.
 */
@Composable
fun SystemBarsEffect(immersive: Boolean, lightBars: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, immersive, lightBars) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previousLightStatus = controller.isAppearanceLightStatusBars
        val previousLightNavigation = controller.isAppearanceLightNavigationBars

        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = previousLightStatus
            controller.isAppearanceLightNavigationBars = previousLightNavigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
