plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версия приложения: увеличивается с каждым обновлением (см. CHANGELOG.md)
val appVersionCode = 8
val appVersionName = "1.5"

base {
    // APK получает имя с версией и сборкой: BapukTV-<версия>-<open|full>-release.apk
    archivesName.set("BapukTV-$appVersionName")
}

android {
    namespace = "com.bapuk.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bapuk.tv"
        minSdk = 21
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // Две сборки из одного кода:
    //  open — публичная, без встроенных плейлистов;
    //  full — со встроенными плейлистами из app/src/full/res/values/presets.xml
    //         (файл не хранится в репозитории; без него full = open).
    flavorDimensions += "edition"
    productFlavors {
        create("open") { dimension = "edition" }
        create("full") { dimension = "edition" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем debug-ключом, чтобы APK можно было сразу поставить на ТВ.
            // Для публикации замените на свой keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
