plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFilePath = System.getenv("TUNELYST_RELEASE_STORE_FILE")?.takeIf(String::isNotBlank)
val releaseStorePassword = System.getenv("TUNELYST_RELEASE_STORE_PASSWORD")?.takeIf(String::isNotBlank)
val releaseKeyAlias = System.getenv("TUNELYST_RELEASE_KEY_ALIAS")?.takeIf(String::isNotBlank)
val releaseKeyPassword = System.getenv("TUNELYST_RELEASE_KEY_PASSWORD")?.takeIf(String::isNotBlank)
val releaseSigningInputs = listOf(releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningRequested = releaseSigningInputs.any { it != null }
val releaseSigningComplete = releaseSigningInputs.all { it != null }

if (releaseSigningRequested && !releaseSigningComplete) {
    throw GradleException(
        "Release signing is incomplete. Configure TUNELYST_RELEASE_STORE_FILE, " +
            "TUNELYST_RELEASE_STORE_PASSWORD, TUNELYST_RELEASE_KEY_ALIAS, and " +
            "TUNELYST_RELEASE_KEY_PASSWORD using a secure local or CI secret store."
    )
}

val releaseStoreFile = releaseStoreFilePath?.let { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) candidate else rootProject.file(path)
}
if (releaseSigningComplete && releaseStoreFile?.isFile != true) {
    throw GradleException("The configured Tunelyst release keystore file does not exist.")
}
val releaseSigningConfigured = releaseSigningComplete && releaseStoreFile?.isFile == true

android {
    namespace = "com.tunelyst.music"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.tunelyst.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 71
        versionName = "1.1.59"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI emulators can take longer to answer install commands, especially on API 26.
    installation {
        timeOutInMs = 300_000
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // Never silently produce a public release signed with the Android debug key.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Fail before release packaging begins if no user-controlled production key was supplied.
val currentProject = project
gradle.taskGraph.whenReady(
    object : org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        override fun execute(graph: org.gradle.api.execution.TaskExecutionGraph) {
            val releasePackagingRequested = graph.allTasks.any { task ->
                task.project == currentProject && (
                    task.name == "assembleRelease" ||
                        task.name == "bundleRelease" ||
                        task.name.startsWith("packageRelease") ||
                        task.name.startsWith("signRelease")
                    )
            }
            if (releasePackagingRequested && !releaseSigningConfigured) {
                throw GradleException(
                    "Release APK/AAB packaging is blocked because no production keystore is configured. " +
                        "Set the four TUNELYST_RELEASE_* environment variables from a secure secret store. " +
                        "Debug builds and tests do not require release credentials."
                )
            }
        }
    }
)

dependencies {
    implementation(project(":core:logic"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // The wavy progress ring uses the Material 3 expressive API; stable 1.4.0 does not include it.
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-core")
    // Keep only the icon classes this app uses, avoiding the full 36 MB extended icon archive.
    implementation(files("libs/material-icons-slim.jar"))

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("io.github.kyant0:taglib:1.0.5")

    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
