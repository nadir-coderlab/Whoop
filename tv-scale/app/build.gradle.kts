plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mizani.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mizani.tv"
        minSdk = 26          // PBKDF2WithHmacSHA256 + java.util.Base64
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    // مفتاح توقيع ثابت داخل المستودع عشان كل تحديث ينزل فوق القديم بدون حذف.
    // التطبيق للاستخدام الشخصي ويتثبّت يدوي؛ ما فيه أي سر داخله.
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/mizani-tv.jks")
            storePassword = "mizani-tv"
            keyAlias = "mizani"
            keyPassword = "mizani-tv"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    systemProperty("sealOut", System.getenv("SEAL_OUT") ?: "")
}
