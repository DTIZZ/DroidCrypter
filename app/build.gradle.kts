import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: credentials only from ~/.gradle/gradle.properties (never the repo).
val userGradleProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".gradle/gradle.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val releaseStoreFile: String? = userGradleProps.getProperty("DROIDCRYPTER_STORE_FILE")
    ?: userGradleProps.getProperty("droidcrypterStoreFile")
val releaseStorePassword: String? = userGradleProps.getProperty("DROIDCRYPTER_STORE_PASSWORD")
    ?: userGradleProps.getProperty("droidcrypterStorePassword")
val releaseKeyAlias: String? = userGradleProps.getProperty("DROIDCRYPTER_KEY_ALIAS")
    ?: userGradleProps.getProperty("droidcrypterKeyAlias")
val releaseKeyPassword: String? = userGradleProps.getProperty("DROIDCRYPTER_KEY_PASSWORD")
    ?: userGradleProps.getProperty("droidcrypterKeyPassword")

if (releaseStoreFile != null && !File(releaseStoreFile).isFile) {
    throw GradleException("DROIDCRYPTER_STORE_FILE is set in ~/.gradle/gradle.properties but '$releaseStoreFile' does not exist")
}

base {
    archivesName.set("DroidCrypter")
}

android {
    namespace = "app.droidcrypter"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.droidcrypter"
        minSdk = 30
        targetSdk = 37
        versionCode = 11101
        versionName = "1.11.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = File(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("DroidCrypter: no DROIDCRYPTER_STORE_FILE in ~/.gradle/gradle.properties; release APK will be unsigned.")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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

    testOptions {
        unitTests {
            // Robolectric layout tests (BrowserLayoutTest) inflate the real Compose UI on the host.
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
            }
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module") }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.bcprov)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.fragment.ktx)
    implementation(libs.biometric)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.muxer)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)

    // Host-side Compose layout tests: JUnit 4 + Robolectric, run by the JUnit Platform via vintage.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val target = File(releaseDir, "DroidCrypter.apk")
        val otherApk = releaseDir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk") && it.name != "DroidCrypter.apk" }
        if (otherApk != null) {
            otherApk.copyTo(target, overwrite = true)
        }
        if (target.isFile) {
            val rootTarget = file("${rootDir}/DroidCrypter.apk")
            target.copyTo(rootTarget, overwrite = true)
        }
    }
}

