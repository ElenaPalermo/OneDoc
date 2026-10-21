# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык общения (обязательно)
Всегда общайся с пользователем на русском языке. На русском пишутся ответы, планы, пояснения, комментарии в коде, KDoc, сообщения коммитов и описания PR. Идентификаторы в коде (классы, функции, переменные, ресурсы) — на английском; строки интерфейса — в `strings.xml` (русская локализация по умолчанию).

## Цель проекта
Приложение **DocFlow Reader** (рабочее название; модуль `:app`, пакет `com.example.fileopener`) — нативный офлайн-просмотрщик документов для Android: PDF, Word (.docx, .doc), Excel (.xlsx, .xls, .csv) и TXT из памяти устройства, почты и мессенджеров. Стек: Kotlin, Jetpack Compose + Material 3, Coroutines, Room, Android Intent Framework. Архитектура: MVVM + Clean Architecture.

## Текущее состояние
План реализации: `C:\Users\elena\.claude\plans\concurrent-crafting-kettle.md`. Этапы 1–3 выполнены: зависимости, слои domain/data/ui, Room, DataStore, навигация; SAF-библиотека (папки и отдельные файлы, автосканирование при запуске, pull-to-refresh), «Недавние», intent-фильтры VIEW/SEND, просмотр TXT; этап 4 — общий каркас просмотрщика; этап 5 — PDF; этап 6 — DOCX/DOC; этап 7 — CSV/XLSX/XLS. Этап 8 (большие файлы, полировка, ландшафт): главный экран доделан, замеры выполнены, все экраны проверены в ландшафте.
- Главный экран (`ui/home/`, `ui/library/`, `ui/common/DocumentRow.kt`): поиск по имени в верхней панели, фильтр-чипы по типу, сортировка, список/сетка (`LibraryPreferences` в DataStore). Меню документа (⋮ и долгое нажатие): «Поделиться», «Переименовать», «Удалить», в «Недавних» — «Убрать из недавних». От 600 dp ширины вкладки — в `NavigationRail`.
- Переименование/удаление: `DocumentStorage.rename/delete` (`DocumentsContract`, для копий вложений — `File`), затем `DocumentRecordsRepository` (`DocumentRecordsDao`) переносит или стирает записи во всех таблицах: библиотека, «Недавние», позиция, закладки. «Поделиться» копий из `filesDir/incoming` — через `FileProvider` (`${applicationId}.files`).
- Миниатюры PDF: `data/thumbnail/PdfThumbnailProvider` (LRU в памяти → JPEG в `cacheDir/thumbnails` → рендер первой страницы Pdfium), в Compose — `LocalPdfThumbnails`/`rememberPdfThumbnail`.
- Замеры на эмуляторе (лог `DocFlowPerf`, пишет `ViewerViewModel`): PDF 80 МБ / 400 страниц готов за ~0,1 с; XLSX 80 МБ (1 млн строк × 10 колонок, 368 МБ XML) — первые строки за ~0,2 с, полный разбор листа в фоне ~3,7 с в недебажной сборке (~12,7 с в debug: debuggable-код ART оптимизирует слабее, замерять скорость лучше с `isDebuggable = false`), ~120 МБ PSS. Поиск по листу идёт по уже разобранным строкам и дополняется по ходу разбора.
- Таблицы (`reader/sheet/`, `reader/xlsx/`, `reader/xls/`, `reader/csv/`, `ui/viewer/sheet/`): парсеры реализуют `SpreadsheetSource` и потоково отдают строки в `SheetSink`. Лист пишется в `SheetRowStore` — временный бинарный файл (`cacheDir/sheets/`) с индексом смещений строк; первые строки публикуются после 100 строк, остальные — пачками, поэтому сетка показывается до конца разбора. `SheetSession` (во ViewModel) разбирает листы лениво, держит LRU окон строк (`SheetData`), ищет по ячейкам после окончания разбора листа. Значения форматируются при разборе (`CellFormatter`): в кэш попадает готовый текст.
- Листы и общие строки XLSX читает `reader/xml/XmlScanner` — свой побайтовый разбор UTF-8 (в 5 раз быстрее `XmlPullParser` Android; другие кодировки перекодируются на лету); стили, книга и связи — по-прежнему `XmlPullParser`. «Общий» формат для простой записи числа (`835765.10`, ≤10 значащих цифр) получается из текста без `Double` (`CellFormatter.formatNumberText`/`generalText`): путь через `BigDecimal` стоил ~6 с на 9 млн чисел.
- Release-сборка сейчас падает при запуске: R8 с `packageScope` на `kotlin.**` даёт `IllegalAccessError` (класс `kotlin.sequences.*`, расширяемый кодом приложения, становится недоступен). Не исправлено.
- `CellFormatter` использует `DataFormatter` из ядра POI, но многосекционные форматы (`плюс;минус;ноль`) разбирает сам: иначе POI уходит в `CellFormat` с `java.awt.Color`, которого нет на Android. По той же причине цвета XLS берутся из `PaletteRecord`, а не из `HSSFColor`. «Общий» формат — свой быстрый путь (10 значащих цифр), он вдвое ускоряет разбор больших листов.
- `SheetViewer`: строки — `LazyColumn` фиксированной высоты, колонки — своя горизонтальная прокрутка (`scrollable`); компонуются только видимые колонки, смещение читается на этапе размещения. Зум во время щипка — `graphicsLayer`, по окончании жеста сетка перекладывается в новом размере шрифта (текст чёткий). Текст выровненных влево ячеек заходит на пустые соседние, справа от данных — 3 пустые колонки; не влезающее число показывается как `###`. Позиция: `index` — строка листа, `sheet`, `column` — первая видимая колонка, `zoom`.
- Pinch-to-zoom сетки проверяет `SheetViewerZoomTest`. `connectedDebugAndroidTest` удаляет приложение после прогона (вместе со скриншотами в `Android/data/...`); чтобы их забрать, ставить `installDebug installDebugAndroidTest` и запускать `adb shell am instrument -w -e class <тест> com.example.fileopener.test/androidx.test.runner.AndroidJUnitRunner`.
- Документы с переносом (`reader/flow/`, `ui/viewer/flow/`): TXT, DOCX, DOC приводятся к общей модели `FlowDocument` (блоки: абзац с `TextRun`, строка таблицы, картинка, разрыв страницы); загрузка — `FlowDocumentLoader`. Таблицы в `FlowDocument.of` режутся на `TableRowBlock`, чтобы `LazyColumn` не компоновал их целиком. Позиция — индекс блока + смещение в пикселях; оглавление — из заголовков 1–3 уровня. Pinch-to-zoom меняет размер шрифта, а не масштаб. Цвета текста с контрастом к фону темы ниже 3:1 заменяются цветом темы.
- DOCX (`reader/docx/`) читается через `ZipFile`, поэтому нужен локальный файл (`localFile` в загрузчике); картинки достаются из архива лениво. DOC (`reader/doc/`) — POI HWPF, картинки хранятся в памяти, размеры шрифтов нормализуются к самому частому размеру. Фикстуры — `app/src/test/resources/word/`.
- PDF (`reader/pdf/`, `ui/viewer/pdf/`): `PdfDocumentHandle` — абстракция над Pdfium (реализация `PdfiumDocumentHandle`), чтобы логику тестировать на JVM с `FakePdfHandle`. `PdfSession` живёт во ViewModel: размеры страниц (догружаются в фоне), оглавление, поиск, LRU-кэш битмапов; все вызовы Pdfium — под мьютексом, документ закрывается в `applicationScope`. `PdfViewer`: базовый рендер по ширине экрана, при увеличении видимый фрагмент дорисовывается в полном разрешении; темы — `ColorMatrix` (белый→страница, чёрный→текст). Для PDF `ReadingPosition.offset` — в 1/10000 высоты страницы.
- Pinch-to-zoom в PDF проверяется инструментальным тестом `PdfViewerZoomTest` (UiAutomator; `adb input` мультитач не умеет).
- Поворот экрана пересоздаёт Activity. `PdfViewer` сохраняет состояние списка своим `Saver` (страница + доля её высоты): пиксельное смещение после поворота указывало бы на другое место страницы. `FlowViewer` ограничивает колонку текста шириной ~680 dp (растёт вместе со шрифтом), строки — без обрезки межстрочного интервала (`LineHeightStyle.Trim.None`). У таблиц шапка закреплена, поэтому верхний отступ сетки при скрытых панелях анимируется к нулю (`ViewerScreen`).
- Каркас просмотрщика (`ui/viewer/common/`): `ViewerScaffold` — панели поверх документа, скрываются тапом по центру (`centerTap`) вместе с системными (`SystemBarsEffect`, immersive); боковая панель закладок (`BookmarksDrawerContent`); `ReaderThemeProvider` подменяет MaterialTheme палитрой темы чтения; `pinchToZoom` перехватывает только щипок двумя пальцами (проход `Initial`), не ломая прокрутку. Новый просмотрщик получает `contentPadding` из каркаса и сообщает позицию через `ViewerViewModel.onPositionChanged`, а переходы (закладки, оглавление) берёт из `navigationRequests`.
- `ViewerViewModel` сохраняет позицию с задержкой 1 с, а при `ON_STOP`/`onCleared` — сразу через `AppContainer.applicationScope`. `ReaderSettings.theme == null` означает «по системной теме».
- Входящие файлы (`ui/incoming/IncomingViewModel`, `SafDocumentStorage.importIncoming`): если провайдер даёт persistable-доступ — храним исходный URI, иначе копируем в `filesDir/incoming/<хэш имени и размера>/` и дальше работаем с `file://` URI.
- Отдельные файлы библиотеки хранятся в псевдопапке `LibraryFolder.SINGLE_FILES_URI`.
- Проверка на эмуляторе (`Medium_Phone_API_37.0`): в Git Bash для путей `/sdcard/...` в adb нужен `MSYS_NO_PATHCONV=1`; открыть файл как из чужого приложения — `adb shell am start -a android.intent.action.VIEW -t text/plain -d content://media/external/file/<id> --grant-read-uri-permission -n com.example.fileopener/.MainActivity`.

## Основные задачи (из ТЗ)
- **Платформа:** Android 8.0+ (minSdk 26), 100% функций без интернета.
- **«Открыть с помощью…»:** intent-filter `VIEW`/`SEND` с MIME-типами всех форматов, чтобы приложение предлагалось из Gmail, Яндекс Почты, Telegram, WhatsApp, Chrome, файловых менеджеров.
- **Доступ к файлам — только SAF** (решение принято): папки через `OpenDocumentTree` с persistable-разрешениями, файлы через `OpenMultipleDocuments`; без `MANAGE_EXTERNAL_STORAGE`. Входящие временные URI копировать во внутренний кэш, чтобы работали «Недавние» и позиция.
- **Главный экран:** библиотека (автосканирование выбранных папок), вкладка «Недавние», список/сетка с иконками типов, размером, датой и миниатюрами PDF; фильтр по типу, мгновенный поиск по имени, сортировка (дата/имя/размер); переименование, удаление, «Поделиться».
- **Общее для просмотрщиков:** автосохранение позиции (страница/ячейка/скролл), immersive-режим, показ/скрытие панелей тапом по центру, плавный скролл, pinch-to-zoom, темы Светлая/Тёмная/Сепия, закладки с быстрым переходом.
- **PDF:** переход к странице (ползунок/ввод), интерактивное оглавление, поиск по тексту с подсветкой.
- **DOCX/DOC/TXT:** reflow под ширину экрана, выбор шрифта и размера, межстрочный интервал.
- **XLSX/XLS/CSV:** нижние вкладки листов, табличная сетка с форматированием чисел/дат/текста, фиксация строки заголовков, чёткий зум сетки, поиск по ячейкам листа.
- **UI/UX:** Material Design 3, Dynamic Colors (Android 12+), портрет и ландшафт с перевёрсткой.
- **Производительность:** открытие PDF/Excel 50–100 МБ за 2–3 с, без лагов на больших таблицах; ленивая подгрузка и кэширование, контроль RAM.
- **Хранение:** Room — история, закладки, позиции чтения, папки библиотеки; DataStore — пользовательские настройки.

## Технические решения
- **DOCX/XLSX — собственные потоковые парсеры** (ZIP + `XmlPullParser`), без полного Apache POI. POI (`poi` HSSF + `poi-scratchpad` HWPF) — только для старых `.xls` и `.doc`. CSV — свой парсер с автоопределением разделителя и кодировки.
- **PDF:** всё на Pdfium (`io.legere:pdfiumandroid`): рендер, оглавление, поиск с координатами, PDF с паролем. От `PdfRenderer` отказались: один движок — один открытый документ и общая система координат для рендера и подсветки. Версия зафиксирована на **2.0.1**: 2.0.2+ собраны на Kotlin 2.4 и не читаются компилятором Kotlin 2.2 из AGP. Нативные библиотеки Pdfium — ~18 МБ на 4 ABI в APK (в AAB Play раздаст по ~5 МБ на устройство).
- **DI:** ручной `AppContainer` в `Application` (без Hilt).
- **Раскладка пакетов:** `domain/` (модели, интерфейсы репозиториев, use cases), `data/` (Room, SAF-хранилище, DataStore, репозитории), `reader/` (движки форматов: `pdf/`, `docx/`, `doc/`, `xlsx/`, `xls/`, `csv/`, `txt/`), `ui/` (`library/`, `recent/`, `viewer/...`, `theme/`).

## Commands
Run from the repo root on Windows with `.\gradlew.bat` (or `./gradlew` in Git Bash).

- Build debug APK: `./gradlew :app:assembleDebug`
- Install on a connected device/emulator: `./gradlew :app:installDebug`
- Unit tests (JVM): `./gradlew :app:testDebugUnitTest`
- Single unit test: `./gradlew :app:testDebugUnitTest --tests "com.example.fileopener.domain.model.DocumentFormatTest"`
- Instrumented tests (needs a device/emulator): `./gradlew :app:connectedDebugAndroidTest`
- Single instrumented test: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.fileopener.data.db.AppDatabaseTest`
- Lint: `./gradlew :app:lintDebug`

## Build setup (non-obvious)
- AGP 9.x with built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin, only `org.jetbrains.kotlin.plugin.compose`. Don't add the Kotlin Android plugin. KSP 2.3.x (`com.google.devtools.ksp`) и Room Gradle-плагин с built-in Kotlin работают. Версия `kotlin` в каталоге (плагины compose/serialization) должна соответствовать Kotlin, встроенному в AGP, — не поднимать её по подсказке lint `NewerVersionAvailable`.
- `compileSdk` uses the new DSL (`compileSdk { version = release(37) }`); targetSdk 37, minSdk 26, Java 11.
- All dependency/plugin versions live in `gradle/libs.versions.toml`; Compose library versions come from the Compose BOM, so add Compose artifacts without versions.
- Release builds enable R8 via the `optimization { }` block (not `isMinifyEnabled`). Its `packageScope` limits R8 to `androidx.**`, `kotlin.**` and `kotlinx.**`, so app code is not shrunk or obfuscated unless that scope is widened.
- There is no `proguard-rules.pro`: R8 keep rules go in `app/src/main/keepRules/` (`rules.keep`), and AGP picks up every file in that folder (сюда же — правила для POI и Pdfium).
- Room-схемы экспортируются в `app/schemas/` (плагин `androidx.room`) и должны храниться в репозитории. При изменении сущностей — поднять `version` в `AppDatabase` и добавить миграцию.
- В `gradle.properties` демону задан `-XX:+UseParallelGC`: JBR 25 из Android Studio падал с `EXCEPTION_ACCESS_VIOLATION` в потоках G1 GC.
- Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`), so a repository added in a module build file breaks the build.

## Code layout
- Слои: `domain/` (модели; интерфейсы репозиториев в `domain/repository/Repositories.kt`; use cases), `data/db/` (Room: сущности, DAO, `AppDatabase`), `data/repository/` (реализации на Room), `data/settings/` (DataStore), `di/AppContainer.kt` (ручной DI, экземпляр в `DocFlowApp`). ViewModel создаются через `viewModelFactory { initializer { ... } }` с расширением `CreationExtras.appContainer` из `DocFlowApp.kt`.
- Навигация — Navigation Compose с типобезопасными маршрутами (`@Serializable`) в `ui/navigation/AppNavHost.kt`.
- `DocumentFormat.detect()` определяет формат сначала по расширению, затем по MIME (мессенджеры часто шлют `application/octet-stream`).
- Иконки — векторные drawable `res/drawable/ic_*.xml` (material-icons не подключены); цвета и иконки типов документов — `ui/common/DocumentVisuals.kt`.
- UI is Compose + Material 3. `ui/theme/Theme.kt` defines `FileOpenerTheme` (wrap all screens in it); `Color.kt`/`Type.kt` hold palette and typography. `dynamicColor` defaults to `true`, so on Android 12+ the wallpaper-based scheme replaces the `Color.kt` palette. Темы чтения (Светлая/Тёмная/Сепия) — отдельно от темы приложения.
- `MainActivity` calls `enableEdgeToEdge()`, so screens must apply the `Scaffold` inner padding / window insets.
- `AndroidManifest.xml` declares only the launcher intent filter. Opening files from other apps will need additional `VIEW`/`SEND` intent filters with MIME types on the activity.
