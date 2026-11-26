plugins {
    id("com.android.application")
}

val appVersionCode = 20
val appVersionName = "0.3.6"

// release 签名：只在设置了下面这几个环境变量时启用（GitHub Actions 的发布流程会设置）。
// 没设置时 release 包不签名，debug 包照常用本机的 debug 密钥。
val releaseKeystoreFile: String? = System.getenv("QDUO_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("QDUO_KEYSTORE_PASSWORD")

base {
    archivesName.set("QDuoDroid-v$appVersionName-build$appVersionCode")
}

android {
    namespace = "dev.xueshi.qduodroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.xueshi.qduodroid"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystoreFile != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = "qduodroid"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20240303")
}
