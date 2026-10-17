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

    // مفتاح التوقيع ما ينحفظ في المستودع: workflow البناء يفكّه من GitHub Actions secrets
    // ويمرّر مكانه وكلمات مروره بمتغيرات بيئة. بدونها تطلع نسخة release بدون توقيع.
    val keystore = System.getenv("MIZANI_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("MIZANI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MIZANI_KEY_ALIAS") ?: "mizani"
            keyPassword = System.getenv("MIZANI_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
