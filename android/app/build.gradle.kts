import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mobilemcp.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mobilemcp.pro"
        minSdk = 30
        targetSdk = 34
        versionCode = 60009
        versionName = "6.0.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// Official prebuilt JNI runtime. Keep binaries out of git and verify the exact
// release on every build; a failed download must never become a usable AAR.
val sherpaAar = rootProject.file("voice-runtime/sherpa-onnx-1.13.8.aar")
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
if (!sherpaAar.isFile || sha256(sherpaAar) != sherpaSha256) {
    if (gradle.startParameter.isOffline) {
        throw GradleException("Run a build online once to download the verified PRIME voice runtime.")
    }
    sherpaAar.parentFile.mkdirs()
    val partial = File(sherpaAar.parentFile, sherpaAar.name + ".part")
    logger.lifecycle("Downloading PRIME offline voice runtime (48 MB)…")
    try {
        val conn = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL().openConnection()
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(sha256(partial) == sherpaSha256) { "PRIME voice runtime checksum mismatch" }
        Files.move(partial.toPath(), sherpaAar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        partial.delete()
    }
}

dependencies {
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
