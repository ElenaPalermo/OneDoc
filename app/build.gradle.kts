plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.doccraftstudio.onedoc"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.doccraftstudio.onedoc"
        minSdk = 26
        targetSdk = 37
        // versionCode растёт на 1 с каждой загрузкой в Google Play (повторить номер Play не даст);
        // versionName — MAJOR.MINOR.PATCH, его видят пользователи.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Upload-ключ для Google Play. Путь и пароли — в ~/.gradle/gradle.properties (вне репозитория);
    // без них release собирается неподписанным.
    val uploadStoreFile = providers.gradleProperty("ONEDOC_UPLOAD_STORE_FILE").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("ONEDOC_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ONEDOC_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ONEDOC_UPLOAD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            if (uploadStoreFile != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
        // Вариант, на котором генерируется Baseline Profile, должен собираться без R8: иначе профиль записан
        // в обфусцированных именах этой сборки, не совпадающих с именами release, и R8 выбрасывает почти все
        // правила приложения. Плагин отключает только isMinifyEnabled, а R8 у нас включён блоком optimization.
        maybeCreate("nonMinifiedRelease").apply {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // locales_config из values-* — язык приложения в системных настройках Android 13+.
    androidResources {
        generateLocaleConfig = true
    }
    // Язык меняется внутри приложения, поэтому в AAB нужны все языки, а не только язык системы.
    bundle {
        language {
            enableSplit = false
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Профиль генерирует модуль :baselineprofile (./gradlew :app:generateBaselineProfile на подключённом
// устройстве) и кладёт в src/release/generated/baselineProfiles/ — он хранится в репозитории и попадает в AAB.
// При обычной сборке не перегенерируется.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.pdfium.android)
    implementation(libs.poi.scratchpad)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)
    // Устанавливает Baseline Profile при установке не из Play (sideload, adb) и для замеров.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // XmlPullParser для JVM-тестов парсеров (на устройстве используется встроенный в Android).
    testImplementation(libs.kxml2)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
