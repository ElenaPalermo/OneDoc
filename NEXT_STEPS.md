# Следующие задачи

Порядок — по приоритету. Перед началом прочитать `CLAUDE.md` (обязательные правила и технические решения) и `PROJECT_STATUS.md` (текущее состояние).

Общие правила для каждой задачи:
- после изменений проходят `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`;
- коммит — только по просьбе пользователя, сообщение на русском, прямо в `main`, без push;
- приложение не удалять (`adb uninstall` и `connectedDebugAndroidTest` запрещены). Ставить через `installDebug` или `adb install -r`, тесты запускать через `am instrument`;
- эмуляторы не перезапускать без просьбы пользователя.

---

## 1. Подготовка к публикации в Google Play
**Что сделать**
1. Согласовать с пользователем финальный пакет (например, `ru.<домен>.docflow`) и название.
2. Сменить `applicationId`.
   - `namespace` и пакеты исходников менять отдельно, если пользователь захочет.
   - Authority `FileProvider` (`${applicationId}.files`) подстроится сам. Тестовый провайдер `com.example.fileopener.test.attachments` и `am instrument`-команды обновить.
3. Иконка приложения: адаптивная (foreground/background + monochrome для тематических иконок Android 13+) вместо шаблонной.
4. Ключ подписи release:
   - keystore — вне репозитория;
   - пароли — в `~/.gradle/gradle.properties` или переменных окружения;
   - `signingConfigs` в `app/build.gradle.kts`.
5. Сборка `:app:bundleRelease` (AAB).
6. Версия: схема `versionCode`/`versionName`.
7. Тексты для Play: политика конфиденциальности (приложение не собирает данные и работает офлайн), анкета Data safety, описание и скриншоты.
8. Проверить `backup_rules.xml` и `data_extraction_rules.xml`: что попадает в резервную копию (Room, DataStore, `filesDir/incoming`).

**Критерии готовности**
- `./gradlew :app:bundleRelease` собирает подписанный AAB.
- Release-APK из этого AAB, поставленный поверх, запускается и открывает все 7 форматов: проверить через `bundletool build-apks --connected-device` или установку на эмулятор.
- В `mapping.txt` нет классов, сменивших пакет.
- Иконка корректна в лаунчере API 26 и API 37, в том числе тематическая.
- Инструментальные тесты 10/10 с новым `applicationId`.
- Keystore и пароли не попали в git.

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
- Прокрутка XLSX 80 МБ во время разбора и после: `adb shell dumpsys gfxinfo com.example.fileopener`.
- PDF 80–100 МБ: зум, поиск.
- Входящие файлы из Telegram, WhatsApp, Gmail и Chrome (в том числе с MIME `application/octet-stream`).

**Критерии готовности**
- Открытие файлов 50–100 МБ — не дольше 3 с.
- Доля janky-кадров при прокрутке после разбора — до 5 %.
- Все источники открывают файл и добавляют его в «Недавние»; позиция сохраняется при повторном открытии.

## 4. Baseline Profile (необязательно)
**Что сделать**
- Модуль `:baselineprofile` с генератором: запуск, «Недавние», открытие PDF и таблицы, прокрутка.
- Подключить `androidx.profileinstaller`.
- Учесть, что AGP 9 и встроенный Kotlin могут потребовать проверки совместимости плагина `androidx.baselineprofile`.

**Критерии готовности**
- `baseline-prof.txt` попадает в release-сборку.
- Холодный старт (`am start -W`) и первая прокрутка сетки быстрее, чем без профиля; цифры записаны в `CLAUDE.md`.

## 5. Обновление зависимостей
**Что сделать**
- datastore 1.3.0, kotlinx-serialization-core 1.12.0.
- POI 6.0.0 — отдельным шагом. Не поднимать плагины `kotlin` и Pdfium (см. `CLAUDE.md`).

**Критерии готовности**
- Сборка, 109+ юнит-тестов и lint проходят.
- После POI 6: тесты `XlsReaderTest` и `DocReaderTest` проходят, release с R8 открывает `.xls` и `.doc` без крашей. При необходимости обновлён `poi.keep`.

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
adb shell am instrument -w com.example.fileopener.test/androidx.test.runner.AndroidJUnitRunner
# При двух эмуляторах: adb -s emulator-5554 ... (API 37) / adb -s emulator-5556 ... (API 26),
# для Gradle — ANDROID_SERIAL=emulator-5556 ./gradlew :app:installDebug
```
