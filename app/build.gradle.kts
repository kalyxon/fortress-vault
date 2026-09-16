import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fortress.vault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fortress.vault"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }

            val rawPath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
                ?: props.getProperty("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
                ?: "fortress_vault_release.keystore"
            val resolvedFile = sequenceOf(
                file(rawPath),
                rootProject.file(rawPath),
                rootProject.file(File(rawPath).name),
                rootProject.file("fortress_vault_release.keystore")
            ).firstOrNull { it.exists() && it.isFile }

            val pass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: props.getProperty("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: props.getProperty("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: props.getProperty("KEY_PASSWORD")

            if (resolvedFile != null && resolvedFile.isFile && !pass.isNullOrEmpty() && !alias.isNullOrEmpty() && !keyPass.isNullOrEmpty()) {
                storeFile = resolvedFile
                storePassword = pass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                val debugConfig = getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

val releaseLineageCandidate1 = rootProject.file("../fortress-debug-to-release.lineage")
val releaseLineageCandidate2 = rootProject.file("fortress-debug-to-release.lineage")
val releaseLineage = if (releaseLineageCandidate1.exists()) releaseLineageCandidate1 else releaseLineageCandidate2

val applyReleaseSigningLineage = tasks.register<Exec>("applyReleaseSigningLineage") {
    onlyIf {
        val apksigner = System.getenv("APKSIGNER_PATH")
        !apksigner.isNullOrEmpty() && releaseLineage.exists()
    }
    doFirst {
        val apksigner = System.getenv("APKSIGNER_PATH") ?: return@doFirst
        if (!releaseLineage.isFile) return@doFirst
        commandLine(
            apksigner,
            "sign",
            "--ks", System.getProperty("user.home") + "/.android/debug.keystore",
            "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--next-signer",
            "--ks", System.getenv("KEYSTORE_FILE"),
            "--ks-key-alias", System.getenv("KEY_ALIAS"),
            "--ks-pass", "env:KEYSTORE_PASSWORD",
            "--key-pass", "env:KEY_PASSWORD",
            "--lineage", releaseLineage.absolutePath,
            "--out", layout.buildDirectory.file("outputs/apk/release/app-release-lineage.apk").get().asFile.absolutePath,
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath
        )
    }
    doLast {
        val lineageApk = layout.buildDirectory.file("outputs/apk/release/app-release-lineage.apk").get().asFile
        val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (lineageApk.exists()) {
            lineageApk.copyTo(releaseApk, overwrite = true)
        }
    }
}

afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy(applyReleaseSigningLineage)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
}
