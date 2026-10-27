plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Генерация Baseline Profile и замер холодного старта (Macrobenchmark) для :app.
// Запуск — только на подключённом устройстве API 28+ (эмулятор подходит), адресно через ANDROID_SERIAL:
//   ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
//   ANDROID_SERIAL=emulator-5554 ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
android {
    namespace = "io.doccraftstudio.onedoc.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark не мерит на эмуляторе без явного разрешения: абсолютные цифры там не точны,
        // но сравнения «с профилем / без» для оценки хватает. Точные замеры — на телефоне.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Свой эмулятор или телефон; Gradle Managed Devices не используются (лишний образ и память).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
