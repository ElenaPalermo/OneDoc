package io.doccraftstudio.onedoc.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val TARGET_PACKAGE = "io.doccraftstudio.onedoc"
private const val DOCUMENTS_AUTHORITY = "io.doccraftstudio.onedoc.baselineprofile.documents"
private const val LOAD_TIMEOUT_MS = 15_000L

/**
 * Документы из assets этого модуля. Открылся ли документ, видно по его имени в верхней панели просмотрщика:
 * по содержимому проверять нельзя — сценарий проходит несколько раз, и со второго раза документ открывается
 * на сохранённой позиции (PDF — «2 / 3», текст — не с заголовка).
 */
private val DOCUMENTS = listOf(
    "report.pdf", "notes.docx", "sample.doc", "sales.xlsx", "customers.csv", "notes.txt", "settings.xml",
)

/** Холодный старт до главного экрана и прокрутка библиотеки (если в ней что-то есть). */
fun MacrobenchmarkScope.startAndBrowseLibrary() {
    pressHome()
    startActivityAndWait()
    device.waitForIdle()
    device.findObject(By.scrollable(true))?.let { list ->
        list.fling(Direction.DOWN)
        list.fling(Direction.UP)
    }
}

/**
 * Открывает каждый формат «как из почты»: VIEW с content:// из провайдера этого модуля. Так проходят
 * копирование вложения, загрузчики PDF, DOCX/DOC/TXT/XML и таблиц — и их первая прокрутка.
 *
 * Запускает сам тестовый APK, а не `am start` из шелла: на Android 11+ приложение видит пакет провайдера
 * (и может прочитать URI) только если этот пакет его запустил и выдал доступ — как почтовый клиент.
 */
fun MacrobenchmarkScope.openEveryFormat() {
    val context = InstrumentationRegistry.getInstrumentation().context
    for (document in DOCUMENTS) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("content://$DOCUMENTS_AUTHORITY/$document"))
            .setComponent(ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        check(device.wait(Until.hasObject(By.text(document)), LOAD_TIMEOUT_MS)) {
            "$document не открылся за $LOAD_TIMEOUT_MS мс"
        }
        // Имя появляется вместе с панелями, содержимое догружается следом.
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.let { content ->
            content.fling(Direction.DOWN)
            content.fling(Direction.UP)
        }
        device.pressBack()
        device.waitForIdle()
    }
}
