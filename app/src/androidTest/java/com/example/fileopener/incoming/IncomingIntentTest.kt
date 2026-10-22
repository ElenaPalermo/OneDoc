package com.example.fileopener.incoming

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.fileopener.DocFlowApp
import com.example.fileopener.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Открытие файла из другого приложения: вложение приходит через чужой провайдер без постоянного доступа
 * ([AssetAttachmentProvider]) и без MIME-типа, как из мессенджеров. Приложение должно определить формат
 * по имени, скопировать файл в `filesDir/incoming`, открыть его и записать в «Недавние».
 */
@RunWith(AndroidJUnit4::class)
class IncomingIntentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app = instrumentation.targetContext.applicationContext as DocFlowApp

    @Test
    fun view_copiesAttachmentAndOpensIt() = openAndCheck("view-note.txt") { uri ->
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
    }

    @Test
    fun send_copiesAttachmentAndOpensIt() = openAndCheck("send-note.txt") { uri ->
        Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun openAndCheck(name: String, intent: (Uri) -> Intent) = runBlocking {
        val uri = Uri.parse("content://$AUTHORITY/$ASSET/$name")
        app.startActivity(
            intent(uri)
                .setClass(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("файл не открылся", device.wait(Until.hasObject(By.textContains(FIRST_LINE)), OPEN_TIMEOUT_MS))

        val copy = File(app.filesDir, "incoming").walk().firstOrNull { it.name == name }
        assertNotNull("вложение не скопировано в filesDir/incoming", copy)
        assertTrue(copy!!.readText().startsWith(FIRST_LINE))
        // В «Недавние» документ попадает после открытия просмотрщика — ждём записи.
        val copyUri = Uri.fromFile(copy).toString()
        val recent = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            app.container.recentRepository.observeRecent().first { list -> list.any { it.document.uri == copyUri } }
        }
        assertNotNull("в «Недавних» нет копии $copyUri", recent)
    }

    private companion object {
        const val AUTHORITY = "com.example.fileopener.test.attachments"
        const val ASSET = "incoming.txt"
        const val FIRST_LINE = "Проверка открытия через Intent"
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}
