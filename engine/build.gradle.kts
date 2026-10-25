// Ядро «Просто ТВ»: база каналов, разбор M3U, обновление плейлистов, настройки и плеер.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ru.iptvsimple.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Правила сжатия, которые получит приложение вместе с движком.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

ksp {
    // Схемы базы хранятся в репозитории: по ним потом пишутся миграции.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    api(libs.datastore)
    api(libs.okhttp)

    // Плеер: только HLS и прогрессивные потоки (TS и прочее), сеть через общий OkHttp.
    api(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.extractor)
    implementation(libs.media3.okhttp)
    // FFmpeg из nextlib нужен только для звука (MP2), библиотеки лежат внутри APK.
    implementation(libs.nextlib.ffmpeg)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.work.runtime)

    testImplementation(libs.kotlin.test)
}
