import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signeringsnyckeln läses från keystore.properties i projektets rot.
// Filen skapas av skapa-signeringsnyckel.sh / .ps1 och är undantagen från git.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// Versionsnumret: funktionsversionen står i version.txt, byggnumret sätts av GitHub-bygget,
// så varje ny APK blir en uppdatering av den förra.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val featureVersion = rootProject.file("version.txt").readText().trim()

android {
    namespace = "se.snc.sign"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.snc.sign"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$featureVersion.$buildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Frontkameran för fotot bredvid signaturen
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // Skriver signatur, foto och sigill i den ursprungliga PDF:en
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Läser gamla Word-filer (.doc)
    implementation("org.apache.poi:poi-scratchpad:3.17")
    // Fota dokument: skanner som hittar kanterna, flera sidor
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
}
