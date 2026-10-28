# Следующие задачи

Порядок — по приоритету. Перед началом прочитать `CLAUDE.md` (обязательные правила и технические решения) и `PROJECT_STATUS.md` (текущее состояние).

Общие правила для каждой задачи:
- после изменений проходят `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`;
- коммит — только по просьбе пользователя, сообщение на русском, прямо в `main`, без push;
- приложение не удалять (`adb uninstall` и `connectedDebugAndroidTest` запрещены). Ставить через `installDebug` или `adb install -r`, тесты запускать через `am instrument`;
- к компьютеру бывает подключён личный телефон пользователя (`2139ca7806d2`): `installDebug` ставит на все устройства, поэтому ставить адресно — `ANDROID_SERIAL=<эмулятор>` или `adb -s <эмулятор>`; на телефон — только по просьбе;
- эмуляторы не перезапускать без просьбы пользователя.

---

## ▶ С чего продолжить
**Задача 1 (подготовка к публикации) начата 25.10.2026.** Сделано: пакет, название, правила резервного копирования, иконка, ключ подписи, AAB, версия (пункты 1–6, 8). Release из AAB проверен на эмуляторе API 37. Тексты для Play (пункт 7) написаны в `play/`; обложка и скриншоты (ru/en/it) — в `play/`. Политика опубликована: https://elenapalermo.github.io/OneDoc/privacy.html (GitHub Pages из `docs/`, собирается `play/make_policy_html.py`). Дальше — создать приложение в Play Console и загрузить материалы из `play/` (см. `play/README.md`). `.idea/deploymentTargetSelector.xml` — локальный файл, не коммитить.

Задача 0 (выбор языка) и поддержка XML выполнены и закоммичены.

**Хвосты задачи 0 (необязательно)**
- ~~Название обрезается на 360 dp~~ — исправлено 24.10.2026. При ширине < 480 dp «Вид» и «Папки библиотеки» перенесены в меню ⋮ (`CompactActionsMaxWidth` в `HomeScreen.kt`), название видно полностью на 360 и 411 dp. От 480 dp кнопки остаются в панели.
- ~~На API 26 папка библиотеки показывалась сырым URI~~ — исправлено 24.10.2026.
  - Причина: провайдер «Загрузок» на Android 8 отдаёт для корня `_display_name = null`, а старый запасной вариант (`substringAfterLast("%3A")`) возвращал URI целиком.
  - Теперь имя берётся из идентификатора документа (`LibraryFolder.nameFromTreeUri`, корень «Загрузок» → `Download`).
  - Уже сохранённые имена-URI чинятся при сканировании на старте (`ScanLibraryUseCase`), без миграции Room.
- Возврат к русскому при системном языке без перевода (например, немецком) проверен только логикой ресурсов. На эмуляторе не проверялся: для этого пришлось бы менять системный язык эмулятора.
- Русский выбирался только в `AppLanguageSwitchTest` (косвенно — через исходный язык); строки `values/` не менялись.

## 0. Выбор языка интерфейса (ru / en / it) — ✅ выполнено 24.10.2026
Требования пользователя:
- языки: итальянский, русский, английский;
- пункт «Язык» / «Language» в интерфейсе;
- перевести все элементы интерфейса: кнопки, меню, настройки, ошибки, снэкбары, диалоги;
- стандартные ресурсы Android;
- выбор сохраняется между перезапусками;
- смена без потери данных;
- содержимое документов не переводить;
- не менять пакет и логику открытия файлов;
- без лишних библиотек.

Порядок работы, заданный пользователем:
1. План.
2. Подтверждение.
3. Реализация небольшими этапами.
4. Сборка.
5. Проверка сохранения языка и всех экранов.
6. Обновление этих документов.
7. Отчёт: изменённые файлы, результаты сборки, инструкции для тестирования.

**0.1. Подтверждение — ✅ выполнено.** План подтверждён. Ответы:
- (а) при неподдерживаемой системной локали — русский, `values/` не трогаем;
- (б) логические значения на языке интерфейса — да.

**0.2. Этап 1 — ресурсы — ✅ выполнено.**
- `app_name`, `category_*` и `pdf_page_counter` помечены `translatable="false"`.
- `action_more` уже существовал.
- Lint чистый, `locales_config` генерируется: ru (по умолчанию), en, it.
- `res/values/strings.xml` — добавить `action_more`, `action_language`, `language_title`, `language_system`.
- Создать `res/values-en/strings.xml` и `res/values-it/strings.xml` с полным переводом всех строк и плюралов. Для итальянского — `one`/`many`/`other`, для английского — `one`/`other`. Подписи языков в выборе не переводить: «Русский», «English», «Italiano».
- Создать `res/resources.properties` со строкой `unqualifiedResLocale=ru`.
- `app/build.gradle.kts` — `androidResources { generateLocaleConfig = true }`.
- Готово, когда сборка проходит, а lint не выдаёт `MissingTranslation` и `ExtraTranslation`.

**0.3. Этап 2 — хранение и применение языка — ✅ выполнено.** Отличия от плана:
- `AppLanguageStore` — `object` без состояния, а не поле `AppContainer`: в `attachBaseContext` контейнера ещё нет.
- `AppLanguage` добавлен в `domain/model/Settings.kt`, а не отдельным файлом — там же лежат остальные модели настроек.
- Добавлено отключение разделения AAB по языкам (`bundle { language { enableSplit = false } }`). Lint `AppBundleLocaleChanges`: иначе Play поставил бы только язык системы.
- `CellFormatter.formatBoolean`: en → TRUE/FALSE, it → VERO/FALSO, остальные → ИСТИНА/ЛОЖЬ. `CellFormatterTest` дополнен.

Исходный план этапа:
- Создать `domain/model/AppLanguage.kt`: enum `SYSTEM("")`, `RUSSIAN("ru")`, `ENGLISH("en")`, `ITALIAN("it")`.
- Создать `data/settings/AppLanguageStore.kt` с функциями `current()`, `apply(language, activity)`, `wrap(context)`:
  - на API 33+ — `LocaleManager` (`getSystemService(LocaleManager::class.java)`), `applicationLocales`;
  - на API 26–32 — `SharedPreferences` + `createConfigurationContext`, `Locale.setDefault` и `activity.recreate()`.
- `di/AppContainer.kt` — поле `appLanguageStore`.
- `MainActivity.kt` — `attachBaseContext(AppLanguageStore.wrap(base))`, только на API < 33. Контейнер там ещё недоступен, поэтому функция статическая или в `companion`.
- JVM-тест `AppLanguageTest`: соответствие тегов и значений, неизвестный тег → `SYSTEM`.
- Готово, когда сборка и юнит-тесты проходят.

**0.4. Этап 3 — интерфейс — ✅ выполнено (на устройстве не проверено).**
- Меню ⋮ (`OverflowMenuButton` в `HomeScreen.kt`) скрывается только при активном поиске.
- Иконка пункта — новая `ic_language.xml`; `ic_more_vert` уже был.
- Проверить на 360 dp: в «Библиотеке» теперь 5 кнопок, название сильнее обрезается многоточием.
- Тест `androidTest/.../ui/home/AppLanguageSwitchTest.kt` написан и компилируется, но не запускался.

Исходный план этапа:
- `ui/home/HomeScreen.kt` — кнопка ⋮ (`action_more`) в `actions` верхней панели на обеих вкладках, пункт «Язык», показ диалога. Остальные кнопки не трогать.
- Создать `ui/home/LanguageDialog.kt` — `AlertDialog` с `RadioButton` на 4 варианта. Отмечен текущий; выбор сразу применяется.
- Создать иконку `res/drawable/ic_more_vert.xml`, если её нет.
- Готово, когда выбор языка меняет интерфейс без перезапуска, а вкладка, поиск и прокрутка списка сохраняются.

**0.5. Этап 4 — проверка — ✅ выполнено.**
- **Инструментальные тесты:** 11/11 на API 37 (emulator-5556) и на API 26 (emulator-5554); серийные номера на 24.10 поменялись местами.
- **API 37, интерфейс:** итальянский (главный экран, меню ⋮, диалог языка) и английский (вкладки, меню документа, переименование, меню таблицы, настройки текста, оглавление и закладки PDF). Вкладка «Недавние» сохранилась при смене языка.
- **API 37, сохранение и системные настройки:**
  - после `am force-stop` язык сохраняется (`cmd locale get-app-locales` → `[en]`);
  - «Как в системе» сбрасывает выбор в `[]`;
  - язык, заданный системой через `cmd locale set-app-locales`, отмечается в диалоге.
- **API 26:** итальянский сохраняется после `am force-stop` (`shared_prefs/app_language.xml`, `tag=it`), даты в формате `dd/MM/yy`, поиск PDF — «Nessun risultato». «Predefinita di sistema» возвращает системный язык.
- Скриншоты: `C:\Users\elena\AppData\Local\Temp\claude\C--Users-elena-AndroidStudioProjects-FileOpener\0649b4b8-4c03-4dfb-9b4b-f12773ecca82\scratchpad\lang\`.
- Оба эмулятора оставлены на «Как в системе».

Исходный план этапа:
- Эмуляторы API 37 (emulator-5554) и API 26 (emulator-5556), на каждом все 4 варианта.
- Что проверить на каждом:
  - главный экран: вкладки, сортировка, поиск, пустые состояния, папки;
  - меню документа, диалоги переименования и удаления, снэкбары;
  - просмотрщики PDF, DOCX, TXT, XLSX, CSV: панели, настройки чтения, поиск, оглавление, закладки, переход к странице, пароль PDF, ошибка открытия;
  - даты и размеры в списке.
- Сохранение после `adb shell am force-stop io.doccraftstudio.onedoc` и повторного запуска.
- На API 33+ язык меняется в системных «Настройки → Приложения → OneDoc → Язык».
- Инструментальный `AppLanguageSwitchTest`:
  - выбрать English и проверить «Library»;
  - `recreate()` — язык сохраняется;
  - в конце вернуть исходный язык.

  Плюс все старые тесты — через `am instrument`.
- Готово, когда скриншоты (scratchpad) на всех трёх языках не содержат строк на другом языке, а инструментальные тесты зелёные.

**0.6. Этап 5 — документация — ✅ выполнено.** `CLAUDE.md` дополнен разделом «Язык интерфейса».

Исходный план этапа:
- Обновить `CLAUDE.md` (раздел о локализации), `PROJECT_STATUS.md`, `NEXT_STEPS.md`.
- Коммит — только по просьбе пользователя.

---

## 1. Подготовка к публикации в Google Play
**Решения пользователя (25.10.2026):** `applicationId` — `io.doccraftstudio.onedoc` (вместе с `namespace` и пакетом исходников), название — OneDoc, в резервную копию — только настройки.

**Что сделать**
1. ~~Согласовать финальный пакет и название~~ — `io.doccraftstudio.onedoc`, OneDoc.
2. ~~Сменить `applicationId`~~ — выполнено 25.10.2026.
   - Сменены `applicationId`, `namespace`, пакеты исходников в `main`, `test`, `androidTest`, папка Room-схем (`app/schemas/io.doccraftstudio.onedoc.data.db.AppDatabase/`), тестовый провайдер (`io.doccraftstudio.onedoc.test.attachments`), команды в `CLAUDE.md`.
   - `FileProvider` (`${applicationId}.files`) подстроился сам. Внутренние имена (`DocFlowApp`, `docflow.db`, теги логов `DocFlow*`) не менялись: пользователю они не видны.
   - Проверено: сборка, 138 юнит-тестов, lint, release с R8 (в `mapping.txt` 0 классов сменили пакет), инструментальные тесты 11/11 на API 37 и API 26.
   - На эмуляторах новое приложение стоит рядом со старым `io.doccraftstudio.onedoc` (старое не удалялось).
3. ~~Иконка приложения~~ — выполнено 25.10.2026, 26.10.2026 заменена на рисунок пользователя `art/onedoci.png` (512×512: готовая бирюзовая плитка со скруглением и свечением по краю, в центре листки PDF, Word, Txt/Xml, Excel, внизу крупная надпись «OneDoc» почти во всю ширину). Прежние варианты (`OneDoc_icon.png`, `onedoc.png` без надписи, `onedocicon.png` с мелкой надписью) удалены.
   - Адаптивная: фон — цвет плитки `#03CCD3` (`values/ic_launcher_background.xml`); foreground — внутренняя часть плитки без свечения (из исходника вырезан прямоугольник 58,50–456,462), — 56 dp из 108 по центру: широкая надпись «OneDoc» у нижнего края плитки целиком помещается даже в круглую маску (при 62 dp круг срезал «O» и «c»). Углы плитки за дугой скругления (свечение) заливаются цветом фона с соседних прямых участков края, снаружи плитки до краёв маски — повтор её крайних пикселей, поэтому шва нет; углы скругляет лаунчер; белых полей нет. Остаётся лёгкая светлая подсветка вдоль краёв — она есть в самом рисунке.
   - Monochrome — силуэт листков: пиксели, далёкие от бирюзового фона и не светлые; белые буквы — «дырки», поэтому P, W, T, X читаются.
   - Шаблонные `drawable/ic_launcher_*.xml` и `mipmap-*/ic_launcher*.webp` удалены: при minSdk 26 всегда используется адаптивная иконка из `mipmap-anydpi/`.
   - Иконка для Play — `art/play_icon_512.png`: та же очищенная плитка на 90 % квадрата, снаружи — продолжение её краёв; Play сам скругляет углы.
   - Новая иконка поставлена на телефон пользователя по его просьбе; на эмуляторах не проверялась. Эмуляторы проверялись с первой версией (лаунчер API 37 и API 26, тематическая иконка на API 37 — стиль значков «Minimal» в «Обои и стиль», работает только на главном экране).
   - 26.10.2026 плитки «Word» (W) и «Excel» (X), похожие на значки Microsoft, заменены нейтральными: синий документ с полосками текста «Doc» и тёмно-бирюзовая таблица с сеткой «Sheet» (зелёная фигура за плиткой — тоже бирюзовая). Делает `art/EditIcon.java` из `art/onedoci.png` → `art/onedoc_neutral.png`; цвет «Sheet» нарочно глубже фона иконки, иначе тематическая (монохромная) иконка теряет эту плитку.
   - Цепочка генераторов (запуск без сборки — Java из Android Studio, команды в шапках файлов): `art/EditIcon.java` → `art/TileIconGen.java` (слои `mipmap-*dpi/`, `art/play_icon_512.png`) → `art/EditBanner.java` (обложка Play с иконкой). Константы TileIconGen (обрезка, углы, цвет фона) подобраны под геометрию `art/onedoci.png`; для другого рисунка их нужно измерить заново.
4. ~~Ключ подписи release~~ — выполнено 26.10.2026.
   - Upload-ключ: `C:\Users\elena\keys\onedoc-upload.jks` (PKCS12, alias `upload`, RSA 4096, 10 000 дней, `CN=OneDoc, O=DocCraft Studio`, SHA-256 сертификата `f1d2b478…78e6313`). Пароль сгенерирован, один для хранилища и ключа.
   - Путь, alias и пароли — в `~/.gradle/gradle.properties` (`ONEDOC_UPLOAD_*`), вне репозитория. `signingConfigs.upload` в `app/build.gradle.kts` читает их через `providers.gradleProperty`; без них release собирается неподписанным.
   - `.gitignore` дополнен `*.jks` и `*.keystore`. Проверено: пароля в отслеживаемых файлах нет. Пароль в чат не выводится — пользователь смотрит его в `~/.gradle/gradle.properties` сам.
   - Ключ и пароль пользователь должен сохранить у себя (менеджер паролей, резервная копия файла): без них не выпустить обновление в Play (при Play App Signing upload-ключ можно сбросить через поддержку, но это долго).
5. ~~Сборка `:app:bundleRelease` (AAB)~~ — выполнено 26.10.2026: `app-release.aab` 16,3 МБ, подпись проверена `jarsigner -verify`; `app-release.apk` (assembleRelease) 21,2 МБ подписан тем же ключом. В `mapping.txt` 0 классов сменили пакет.
6. ~~Версия~~ — выполнено 26.10.2026: `versionCode = 1`, `versionName = "1.0.0"`. Схема (комментарий в `app/build.gradle.kts`): `versionCode` растёт на 1 с каждой загрузкой в Play, `versionName` — MAJOR.MINOR.PATCH.
7. Тексты для Play — черновики готовы 26.10.2026, папка `play/` (обзор — `play/README.md`):
   - `listing/{ru-RU,en-US,it-IT}/` — название, краткое и полное описание; длины в пределах лимитов Play (30 / 80 / 4000). В текстах только расширения форматов, без названий чужих брендов.
   - `privacy-policy.md` — политика на трёх языках. Основание: в release-манифесте нет `INTERNET`, в коде нет сети, рекламы и аналитики, в Auto Backup — только настройки. Плейсхолдеры: имя разработчика и почта для связи.
   - `data-safety.md` — Data safety: «данные не собираются и не передаются»; остальные декларации «Содержание приложения» (реклама — нет, доступ без входа, рейтинг, целевая аудитория — решает пользователь).
   - 26.10.2026: обложка `play/feature-graphic.png` (1024×500 из баннера пользователя `art/banner_source.png`), скриншоты `play/screenshots/{ru,en,it}/` по 8 штук 1080×2160 на демо-документах своего языка (эмулятор временно переключался на `wm size 1080x2160` и демо-режим строки состояния, всё возвращено).
   - Подписи групп в интерфейсе — «Документы» / «Таблицы» (en: Documents / Spreadsheets, it: Documenti / Fogli di calcolo) вместо «Word» / «Excel»; в строке документа — сам формат (DOCX, XLSX, CSV…); в тексте пустой библиотеки — список расширений.
   - 26.10.2026: в политику вписаны разработчик DocCraft Studio и почта doccraftstudioinfo@gmail.com; опубликована через GitHub Pages — https://elenapalermo.github.io/OneDoc/privacy.html. Источник — `play/privacy-policy.md`, страницу `docs/privacy.html` собирает `play/make_policy_html.py` (только стандартная библиотека Python).
8. ~~Правила резервного копирования~~ — выполнено 25.10.2026: в `backup_rules.xml` (Android 8–11) и `data_extraction_rules.xml` (12+, облако и перенос на устройство) включены только `datastore/settings.preferences_pb` и `app_language.xml`. База и `filesDir/incoming` не копируются: их `content://` URI и SAF-разрешения на новом устройстве недоступны. Восстановление через `bmgr` не проверялось.

**Критерии готовности**
- ~~`./gradlew :app:bundleRelease` собирает подписанный AAB~~ — 26.10.2026.
- ~~Release-APK из этого AAB запускается и открывает все форматы~~ — проверено 26.10.2026 на эмуляторе API 37 (`emulator-5554`):
  - debug-версия OneDoc и её тестовый APK удалены с этого эмулятора с разрешения пользователя (другая подпись — поверх не встать); старый `com.example.fileopener` остался;
  - APK нарезаны из `app-release.aab` через `bundletool build-apks --connected-device` (base + `split_config.x86_64`) и подписаны debug-ключом, чтобы не передавать пароль upload-ключа в командной строке; подпись самого AAB upload-ключом проверена отдельно;
  - `bundletool` 1.18.3 взят из кэша Gradle (зависимость AGP, без точки входа): запуск `java -cp <bundletool, guava-*-jre, failureaccess, protobuf-java(-util), aapt2-proto, dagger, javax.inject, jose4j, slf4j-api, gson, kotlin-stdlib> com.android.tools.build.bundletool.BundleToolMain`, для `build-apks` нужен `--aapt2=<build-tools>/aapt2.exe`;
  - через `VIEW` открыты все 8 форматов: PDF 238 мс, DOCX 25 мс, DOC 72 мс, XLSX 15 МБ 66 мс (лист 2003 строки — 253 мс), XLS 114 мс, CSV 5,6 МБ 27 мс (100 001 строка — 1,1 с), TXT 16 мс, XML 20 мс; падений нет, содержимое на скриншотах корректно;
  - 27.10.2026 перепроверено после замены подписей групп и иконки: AAB из коммита `3b4f9cb` (16,2 МБ; подпись — тот же upload-ключ, `bundletool validate` без ошибок; в манифесте нет `INTERNET`; в `mapping.txt` 0 классов сменили пакет; загрузка из Play — 4,7–5,2 МБ на устройство). На эмуляторе API 37 все 8 форматов открылись (PDF 186 мс, DOCX 39, DOC 75, XLSX 56, XLS 92, CSV 12, TXT 10, XML 33 мс), падений нет; в лаунчере новая иконка, в библиотеке — «Documents» / «Spreadsheets»;
  - на этом эмуляторе теперь стоит release: инструментальные тесты (debug-подпись) на нём не запустятся, пока release не удалить и не поставить debug обратно.
- ~~В `mapping.txt` нет классов, сменивших пакет~~ — 26.10.2026.
- Иконка корректна в лаунчере API 26 и API 37, в том числе тематическая.
- ~~Инструментальные тесты с новым `applicationId`~~ — 11/11 на API 37 и API 26 (25.10.2026).
- ~~Keystore и пароли не попали в git~~ — проверено 26.10.2026.

## 2. Завершить проверку на API 26 (`Small_Phone`, emulator-5556)
**Что проверить**
- Темы чтения «Тёмная» и «Сепия» во всех просмотрщиках (PDF, документы с переносом, таблицы).
- Immersive-режим: скрытие и показ панелей тапом, возврат системных панелей свайпом.
- Позиция чтения после полного перезапуска: `am force-stop`, затем запуск.
- Открытие файлов из приложения «Файлы» (DocumentsUI) через «Открыть с помощью».
- По согласованию — вернуть `accelerometer_rotation` в 1.

**Критерии готовности**
- Каждый пункт проверен и подтверждён скриншотом в scratchpad.
- Найденные ошибки исправлены; в `CLAUDE.md` дописана строка о проверке API 26.

## 3. Проверка на реальном устройстве
**Что проверить**
- Прокрутка XLSX 80 МБ во время разбора и после: `adb shell dumpsys gfxinfo io.doccraftstudio.onedoc`.
- PDF 80–100 МБ: зум, поиск.
- Входящие файлы из Telegram, WhatsApp, Gmail и Chrome (в том числе с MIME `application/octet-stream`).

**Критерии готовности**
- Открытие файлов 50–100 МБ — не дольше 3 с.
- Доля janky-кадров при прокрутке после разбора — до 5 %.
- Все источники открывают файл и добавляют его в «Недавние»; позиция сохраняется при повторном открытии.

## 4. ~~Baseline Profile~~ — выполнено 27.10.2026
**Что сделано**
- Модуль `:baselineprofile` (`com.android.test`, плагин `androidx.baselineprofile` 1.5.0 — с AGP 9.4.1 и встроенным Kotlin работает; Macrobenchmark 1.5.0). В `app` — `profileinstaller` 1.4.1 и `baselineProfile(project(":baselineprofile"))`.
- `BaselineProfileGenerator`: холодный старт, прокрутка библиотеки, открытие PDF, DOCX, DOC, XLSX, CSV, TXT, XML (из assets модуля) с прокруткой. Документы отдаёт `DocumentProvider` тестового APK; открывает их сам тестовый APK через `startActivity` с `FLAG_GRANT_READ_URI_PERMISSION` — `am start` из шелла не годится: на Android 11+ OneDoc не видит пакет провайдера (AppsFilter) и импорт падает. Открытие проверяется по имени файла в верхней панели: со второго прохода документ открывается на сохранённой позиции. XLS в сценарии нет — нет тестового файла.
- `StartupBenchmark`: холодный старт без предкомпиляции и с профилем, 10 запусков. На эмуляторе Macrobenchmark разрешён явно (`androidx.benchmark.suppressErrors=EMULATOR`).
- Профиль — `app/src/release/generated/baselineProfiles/` (`baseline-prof.txt`, `startup-prof.txt`), хранится в репозитории; при обычной сборке не перегенерируется.
- **Важно:** плагин отключает R8 для варианта генерации через `isMinifyEnabled`, а у нас R8 включён блоком `optimization` — поэтому в `app/build.gradle.kts` для `nonMinifiedRelease` явно `optimization { enable = false }`. Без этого профиль записывался в обфусцированных именах этой сборки, и в release доживало 19 правил OneDoc из 799. Теперь: 26 297 правил, OneDoc — 2 602; после R8 в release — 879 правил OneDoc (остальное R8 встроил), двоичный `baseline.prof` в AAB — 8,4 КБ (было 4,4).
- Запуск только адресно (генерация и замер ставят свои варианты, подписанные upload-ключом, и после прогона удаляют приложение вместе с данными):
  - `ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile`
  - `ANDROID_SERIAL=emulator-5554 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -P android.testInstrumentationRunnerArguments.class=io.doccraftstudio.onedoc.baselineprofile.StartupBenchmark` (результат — `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/*/…-benchmarkData.json`).

**Замеры (эмулятор API 37, x86_64)**
- С профилем, ещё испорченным R8 (19 правил OneDoc): медиана TTID 527,7 мс без компиляции и 509,3 мс с профилем (−3,5 %).
- С исправленным профилем (Pixel 8, API 34, x86_64; 10 холодных стартов): медиана TTID **603,2 мс** без компиляции (541–697) и **540,1 мс** с профилем (482–598) — **−10,5 %**. На ARM-телефоне разница обычно больше.
- Эмулятор `Medium_Phone_API_37.0` после двух зависаний (нехватка памяти на компьютере — 0,5 ГБ свободно из 15,2) не загрузился и после Cold Boot; пользователь завёл новый эмулятор **Pixel 8 API 34** (`emulator-5554`). На нём восстановлены release из AAB и демо-библиотека `Documents/OneDoc` (английские демо-документы и `SampleDoc.doc`).

**Можно позже**
- Замер на реальном телефоне — только с согласия пользователя: прогон удаляет OneDoc с устройства вместе с данными.
- XLS в сценарий генерации (нужен тестовый `.xls`).

## 5. ~~Обновление зависимостей~~ — выполнено 28.10.2026
Весь каталог сверен с Google Maven и Maven Central (только стабильные версии):
- **DataStore 1.2.1 → 1.3.0** — обновлён (`42cf23d`). Собран на Kotlin 2.2.20, совместим. Проверено: сборка, 138 юнит-тестов, lint; настройки (вид библиотеки) сохраняются при установке новой версии поверх старой.
- **kotlinx-serialization-core 1.12.0 — нельзя**: требует `kotlin-stdlib` 2.4.10 (собран на Kotlin 2.4), компилятор Kotlin 2.2 из AGP его не прочитает. Остаётся 1.11.0.
- **POI 6.0.0 — решено не обновлять, остаётся 5.5.1** (решение пользователя). Причины:
  - байткод POI 6 — Java 17 (major 61). Сканер dex release-сборки (сверка всех обращений к API платформы с `platforms/android-37.0/data/api-versions.xml`) нашёл по сравнению с POI 5.5.1 одно новое обращение к API новее Android 8 — `Stream.toList()` (API 34): в конструкторах копирования HWPF `Paragraph/CharacterRun/Section` (через `Range(Range)`), `EscherArrayProperty.getGenericProperties`, `CellFormatPart`, `GenericRecordXmlWriter`, HSLF и HEMF. Наш путь чтения `.doc`/`.xls` их статически не вызывает, на API 34 `.doc` и `.xls` (с картинкой, форматами, двумя листами) открывались — но на Android 8–13 это не проверялось, а выигрыша для нас в POI 6 нет;
  - вернуться к POI 6, если понадобится: проверить на эмуляторе API 26 (`Small_Phone`) открытие `.doc` и `.xls` в release-сборке.
- **Не трогать (как и раньше):** Pdfium 2.0.1 (2.0.2+ на Kotlin 2.4), плагины `kotlin` 2.2.10 (должны совпадать с Kotlin из AGP).
- **Остальное уже последнее стабильное:** AGP 9.4.1, Compose BOM 2026.09.00, core-ktx, Lifecycle, Activity, Navigation, Room, корутины, documentfile, KSP 2.3.12, uiautomator, benchmark/profileinstaller, junit, espresso, kxml2.
- **Можно позже:** в AAB лежат неиспользуемые ресурсы POI (`presetShapeDefinitions.xml` ~540 КБ, `empty.ppt`, таблицы HDGF) — их можно исключить через `packaging.resources.excludes`, чтобы сократить размер.

## 6. Разбор XLSX без промежуточного `SheetCell`
**Что сделать**
- Писать ячейки из `XmlScanner` прямо в `SheetRowStore`, без объекта на ячейку, чтобы снизить нагрузку на сборщик мусора во время прокрутки.

**Критерии готовности**
- Юнит-тесты таблиц проходят.
- Полный разбор XLSX 80 МБ в release не медленнее 3,7 с.
- Доля времени сборщика мусора во время разбора заметно ниже 27 %, и это подтверждено замером.

---

## Команды проверки
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleRelease          # проверка R8

# Инструментальные тесты без удаления приложения:
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w io.doccraftstudio.onedoc.test/androidx.test.runner.AndroidJUnitRunner
# При двух эмуляторах: adb -s emulator-5554 ... (API 37) / adb -s emulator-5556 ... (API 26),
# для Gradle — ANDROID_SERIAL=emulator-5556 ./gradlew :app:installDebug
```
