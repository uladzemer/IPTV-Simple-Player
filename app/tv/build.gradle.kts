import java.util.Properties

// Приложение «Просто ТВ» для приставок Android TV.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Необязательный плейлист M3U, который добавится при первом запуске. Только для локальной
// сборки (-PtvDefaultPlaylistUrl=...). Приватный адрес не коммитить и не передавать в CI.
val tvDefaultPlaylistUrl = providers.gradleProperty("tvDefaultPlaylistUrl").orElse("")

/** Строка для BuildConfig: экранируем обратную косую черту и кавычки. */
fun asJavaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Постоянный ключ подписи релиза. Сам ключ в репозиторий не кладётся:
// - в CI он приходит из секретов через переменные окружения RELEASE_*;
// - локально путь к файлу с параметрами задаётся в ~/.gradle/gradle.properties:
//   iptvSimpleSigningProps=/путь/к/iptvsimple-release.properties
// Без ключа релиз получается неподписанным: так его собирает и подписывает
// своим ключом F-Droid. Для проверки на приставке без ключа — debug-сборка.
val releaseSigningProps = Properties().apply {
    providers.gradleProperty("iptvSimpleSigningProps").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?.takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}
fun releaseSigningValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: releaseSigningProps.getProperty(key)
val releaseStoreFile = releaseSigningValue("RELEASE_KEYSTORE_FILE", "storeFile")

// Версия для зрителя; она же входит в имя готового файла:
// app/tv/build/outputs/apk/release/tv-<версия>-release.apk
val appVersionName = "1.3.7"
base.archivesName = "tv-$appVersionName"

android {
    namespace = "ru.iptvsimple.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.iptvsimple.tv"
        minSdk = 26
        targetSdk = 33
        versionCode = 12
        versionName = appVersionName
        buildConfigField("String", "TV_DEFAULT_PLAYLIST_URL", asJavaStringLiteral(tvDefaultPlaylistUrl.get()))
        // Приставки — только ARM; библиотеки FFmpeg для x86 лишь раздувают APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseSigningValue("RELEASE_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = releaseSigningValue("RELEASE_KEY_ALIAS", "keyAlias")
                keyPassword = releaseSigningValue("RELEASE_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Псевдоязыки помогают заметить обрезанные и несобранные из ресурсов надписи.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // В APK только английский (запасной) и русский; переводы библиотек отбрасываются.
        localeFilters += listOf("en", "ru")
    }

    dependenciesInfo {
        // Зашифрованный список зависимостей для Google Play в APK не кладём:
        // F-Droid и RuStore его не используют, а F-Droid требует его убрать.
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // Служебные файлы библиотек (лицензии, версии модулей) приложению не нужны.
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    // Ядро: база каналов, плейлисты, настройки и плеер.
    implementation(project(":engine"))

    // Окно, жизненный цикл, фоновые задачи.
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.work.runtime)

    // Hilt: внедрение зависимостей во ViewModel и фоновые задачи.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // Интерфейс на Compose для телевизора.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons)
    implementation(libs.tv.material)

    // Логотипы каналов и вывод видео через SurfaceView.
    implementation(libs.coil.compose)
    implementation(libs.media3.ui.compose)

    testImplementation(libs.kotlin.test)
}

// Hilt собирает граф зависимостей приложения своей копией kotlin-metadata-jvm, которая
// отстаёт от компилятора Kotlin и не читает классы новой версии. Подставляем версию,
// совпадающую с Kotlin проекта.
configurations
    .matching { it.name.startsWith("hiltAnnotationProcessor") }
    .configureEach {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
