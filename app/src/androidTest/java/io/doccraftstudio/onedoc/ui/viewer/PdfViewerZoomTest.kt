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
 * Pinch-to-zoom в PDF настоящим жестом двумя пальцами (через UiAutomator, `adb input` мультитач не умеет).
 * Скриншот после увеличения сохраняется в `Android/data/<пакет>/files/pdf_zoom.png` для визуальной проверки резкости.
 */
@RunWith(AndroidJUnit4::class)
class PdfViewerZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext as DocFlowApp

    @Test
    fun pinchOpen_zoomsInAndSavesZoomInPosition() = runBlocking {
        val file = File(app.filesDir, "test-docs/sample.pdf").apply {
            parentFile?.mkdirs()
            instrumentation.context.assets.open("sample.pdf").use { input -> outputStream().use(input::copyTo) }
        }
        val uri = Uri.fromFile(file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .setClass(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("PDF не открылся", device.wait(Until.hasObject(By.text("1 / 30")), OPEN_TIMEOUT_MS))

        val root = device.findObject(By.pkg(app.packageName).depth(0))
        root.pinchOpen(PINCH_PERCENT)
        // Ждём сохранения позиции (задержка 1 с) и дорисовки фрагментов в полном разрешении.
        Thread.sleep(SETTLE_MS)
        device.takeScreenshot(File(app.getExternalFilesDir(null), "pdf_zoom.png"))

        val position = app.container.readingStateRepository.getPosition(uri.toString())
        assertNotNull(position)
        assertTrue("Масштаб не изменился: ${position?.zoom}", position!!.zoom > MIN_EXPECTED_ZOOM)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
        const val PINCH_PERCENT = 0.6f
        const val SETTLE_MS = 2_500L
        const val MIN_EXPECTED_ZOOM = 1.2f
    }
}
