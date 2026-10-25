package io.doccraftstudio.onedoc.ui.viewer.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/** Доли ширины и высоты экрана, считающиеся «центром» для показа/скрытия панелей. */
private const val CENTER_X_START = 0.25f
private const val CENTER_X_END = 0.75f
private const val CENTER_Y_START = 0.2f
private const val CENTER_Y_END = 0.8f

/**
 * Одиночный тап в центральной зоне. Не мешает прокрутке: тап, превратившийся в свайп,
 * поглощается списком и сюда не доходит. Края экрана оставлены под будущие действия (листание PDF).
 */
fun Modifier.centerTap(onTap: () -> Unit): Modifier = composed {
    val currentOnTap by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        detectTapGestures { position ->
            val inCenterX = position.x in size.width * CENTER_X_START..size.width * CENTER_X_END
            val inCenterY = position.y in size.height * CENTER_Y_START..size.height * CENTER_Y_END
            if (inCenterX && inCenterY) currentOnTap()
        }
    }
}

/**
 * Pinch-to-zoom двумя пальцами, совместимый с прокруткой одним пальцем.
 * События перехватываются на проходе [PointerEventPass.Initial], поэтому во время щипка
 * список не прокручивается. [onZoom] получает множитель относительно предыдущего кадра
 * и точку между пальцами (в координатах элемента), относительно которой идёт увеличение.
 */
fun Modifier.pinchToZoom(onZoom: (zoom: Float, centroid: Offset) -> Unit, onZoomEnd: () -> Unit = {}): Modifier = composed {
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnZoomEnd by rememberUpdatedState(onZoomEnd)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        zoomed = true
                        currentOnZoom(zoom, event.calculateCentroid(useCurrent = true))
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (zoomed) currentOnZoomEnd()
        }
    }
}
