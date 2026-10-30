plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Параметры сборки (GitHub Actions передаёт их через -P…):
//   bymeBaseUrl      — адрес сайта, который открывает приложение
//   bymeVersionCode  — номер сборки (растёт с каждой сборкой, иначе Android не обновит приложение)
//   bymeAppId        — applicationId (должен совпадать с настройкой в админке → «Приложение»)
val baseUrl = ((project.findProperty("bymeBaseUrl") as String?) ?: "http://91.98.119.91").trim().trimEnd('/')
val vCode = ((project.findProperty("bymeVersionCode") as String?) ?: "1").toInt()
val vName = (project.findProperty("bymeVersionName") as String?) ?: "1.0.$vCode"
val appId = (project.findProperty("bymeAppId") as String?) ?: "am.snp.shop"

val ksPath: String? = System.getenv("BYME_KEYSTORE")
val hasKeystore = ksPath != null && file(ksPath).exists()

android {
    namespace = "am.snp.shop"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        // http:// (сервер по IP) — разрешаем незашифрованный трафик; с https://домен — запрещаем
        manifestPlaceholders["cleartext"] = baseUrl.startsWith("http://").toString()
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("BYME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BYME_KEY_ALIAS")
                keyPassword = System.getenv("BYME_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    // v1 + v2: некоторые установщики (MIUI и др.) отказываются от APK только с v2-подписью
    signingConfigs.getByName("debug") {
        enableV1Signing = true
        enableV2Signing = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
