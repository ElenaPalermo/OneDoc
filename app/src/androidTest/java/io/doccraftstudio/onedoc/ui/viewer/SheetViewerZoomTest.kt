package io.doccraftstudio.onedoc.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.doccraftstudio.onedoc.DocFlowApp
import io.doccraftstudio.onedoc.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pinch-to-zoom сетки таблицы настоящим жестом двумя пальцами (UiAutomator).
 * Скриншот после увеличения — в `Android/data/<пакет>/files/sheet_zoom.png`: текст должен быть чётким.
 */
@RunWith(AndroidJUnit4::class)
class SheetViewerZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext as DocFlowApp

    @Test
    fun pinch_changesGridZoomAndSavesItInPosition() = runBlocking {
        val file = File(app.filesDir, "test-docs/zoom.csv").apply {
            parentFile?.mkdirs()
            writeText(buildString {
                appendLine("Товар;Количество;Цена")
                for (i in 1..500) appendLine("Товар $i;$i;${i * 10},50")
            })
        }
        val uri = Uri.fromFile(file)
        app.container.readingStateRepository.savePosition(uri.toString(), io.doccraftstudio.onedoc.domain.model.ReadingPosition())
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/csv")
                .setClass(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("Таблица не открылась", device.wait(Until.hasObject(By.text("Товар 1")), OPEN_TIMEOUT_MS))

        val root = device.findObject(By.pkg(app.packageName).depth(0))
        // Жест — только по сетке: на низком экране (720×1280) крайние точки щипка иначе попадают
        // на верхнюю панель, и до сетки доходит один палец. Боковые отступы не нулевые:
        // точка на самом краю (x = ширине экрана) лежит за экраном и отбрасывается.
        val marginX = device.displayWidth / 10
        root.setGestureMargins(marginX, device.displayHeight / 4, marginX, device.displayHeight / 8)
        root.pinchOpen(PINCH_PERCENT)
        // Ждём перекладки сетки и сохранения позиции (задержка 1 с).
        Thread.sleep(SETTLE_MS)
        device.takeScreenshot(File(app.getExternalFilesDir(null), "sheet_zoom.png"))
        val zoomedIn = app.container.readingStateRepository.getPosition(uri.toString())
        assertNotNull(zoomedIn)
        assertTrue("Масштаб не увеличился: ${zoomedIn?.zoom}", zoomedIn!!.zoom > MIN_ZOOM_IN)

        root.pinchClose(PINCH_PERCENT)
        Thread.sleep(SETTLE_MS)
        val zoomedOut = app.container.readingStateRepository.getPosition(uri.toString())
        assertTrue("Масштаб не уменьшился: ${zoomedOut?.zoom}", zoomedOut!!.zoom < zoomedIn.zoom)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
        const val PINCH_PERCENT = 0.6f
        const val SETTLE_MS = 2_500L
        const val MIN_ZOOM_IN = 1.2f
    }
}
