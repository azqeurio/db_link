import java.io.File
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildRootProvider = providers
    .gradleProperty("DBLINK_BUILD_ROOT")
    .orElse(providers.environmentVariable("DBLINK_BUILD_ROOT"))
    .orElse(
        providers.provider {
            File(
                System.getProperty("java.io.tmpdir"),
                "dblink-build/dblink/${project.name}",
            ).absolutePath
        },
    )

layout.buildDirectory.set(file(buildRootProvider.get()))
val nativeSourceRoot = layout.projectDirectory.dir("src/main/cpp")
val stagedNativeRoot = layout.buildDirectory.dir("native-src")
val nativeBuildStagingDirProvider = providers.provider {
    File(buildRootProvider.get()).parentFile.resolve("${project.name}-native-staging").absolutePath
}
val stageNativeSources = tasks.register<Sync>("stageNativeSources") {
    from(nativeSourceRoot)
    into(stagedNativeRoot)
}

tasks.matching { task ->
    task.name.startsWith("configureCMake") || task.name.startsWith("buildCMake")
}.configureEach {
    dependsOn(stageNativeSources)
}

// Android Studio can configure CMake before Gradle's CMake tasks run, so keep
// the staged source tree available during sync as well.
sync {
    from(nativeSourceRoot)
    into(stagedNativeRoot)
}

val googleMapsApiKeyPropertyNames = listOf(
    "GOOGLE_MAPS_API_KEY",
    "googleMapsApiKey",
    "google.maps.api.key",
    "gmap.api.key",
    "maps.api.key",
)
val googleMapsApiKeyFileNames = listOf(
    "com.google.android.geo.API_KEY.xml",
    "google_maps_api_key.xml",
    "google_maps_api_key.properties",
    "google_maps_api_key.txt",
    "gmap_api_key.properties",
    "gmap_api_key.txt",
    "maps_api_key.properties",
    "maps_api_key.txt",
)
val googleMapsApiKeyPropertyPattern = Regex(
    """(?m)^\s*(?:${googleMapsApiKeyPropertyNames.joinToString("|") { Regex.escape(it) }})\s*[:=]\s*(.+?)\s*$""",
)
val googleMapsApiKeyResourcePattern = Regex(
    """(?s)<string\b[^>]*name\s*=\s*["'](?:google_maps_key|google_maps_api_key|maps_api_key|GOOGLE_MAPS_API_KEY)["'][^>]*>\s*([^<]+)\s*</string>""",
)
val googleMapsApiKeyValuePattern = Regex("""android:value\s*=\s*["']([^"']+)["']""")
val googleMapsApiKeyLiteralPattern = Regex("""AIza[0-9A-Za-z_\-]+""")

fun normalizeGoogleMapsApiKey(value: String?): String = value
    ?.trim()
    ?.trim('"', '\'')
    ?.takeUnless { it.isBlank() || it == "YOUR_GOOGLE_MAPS_API_KEY" }
    .orEmpty()

fun readGoogleMapsApiKeyFromFile(file: File): String {
    if (!file.isFile) return ""

    val contents = runCatching { file.readText().trim() }.getOrDefault("")
    if (contents.isBlank()) return ""

    val rawSingleLineKey = contents
        .takeIf {
            it.lineSequence().filter(String::isNotBlank).count() == 1 &&
                !it.contains("=") &&
                !it.contains("<") &&
                it.length in 20..200
        }

    return sequenceOf(
        googleMapsApiKeyPropertyPattern.find(contents)?.groupValues?.getOrNull(1),
        googleMapsApiKeyResourcePattern.find(contents)?.groupValues?.getOrNull(1),
        googleMapsApiKeyValuePattern.find(contents)?.groupValues?.getOrNull(1),
        googleMapsApiKeyLiteralPattern.find(contents)?.value,
        rawSingleLineKey,
    )
        .map(::normalizeGoogleMapsApiKey)
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

fun loadGoogleMapsApiKeyFromLocalFiles(): String {
    val repoRoot = rootProject.layout.projectDirectory.asFile
    val parentRoot = repoRoot.parentFile
    val candidateDirectories = listOfNotNull(
        repoRoot,
        parentRoot,
        parentRoot?.parentFile,
    ).distinctBy { it.absolutePath }
    val candidateFiles = candidateDirectories.flatMap { directory ->
        googleMapsApiKeyFileNames.map { fileName -> directory.resolve(fileName) }
    } + listOfNotNull(
        repoRoot.resolve("local.properties"),
        parentRoot?.resolve("local.properties"),
    )

    return candidateFiles
        .firstNotNullOfOrNull { file ->
            readGoogleMapsApiKeyFromFile(file).takeIf { it.isNotBlank() }
        }
        .orEmpty()
}

val googleMapsApiKeyProvider = providers
    .gradleProperty("GOOGLE_MAPS_API_KEY")
    .orElse(providers.environmentVariable("GOOGLE_MAPS_API_KEY"))
    .orElse(providers.provider { loadGoogleMapsApiKeyFromLocalFiles() })
val googleMapsApiKey = googleMapsApiKeyProvider.get().trim()
val escapedGoogleMapsApiKey = googleMapsApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "dev.dblink"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dblink"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "CAMERA_BASE_URL", "\"http://192.168.0.10\"")
        buildConfigField("int", "CAMERA_CONNECT_TIMEOUT_MS", "10000")
        buildConfigField("int", "CAMERA_READ_TIMEOUT_MS", "30000")
        buildConfigField("int", "CAMERA_LIVE_VIEW_PORT", "65000")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$escapedGoogleMapsApiKey\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-DLIBRAW_NOTHREADS")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "DEBUG_PROTOCOL_LOGS", "true")
            buildConfigField("boolean", "DEBUG_PROTOCOL_WORKBENCH", "true")
        }
        release {
            // Keep CameraX/ML Kit scanner internals stable with explicit keep rules
            // while still allowing release minification for the rest of the app.
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "NONE"
            buildConfigField("boolean", "DEBUG_PROTOCOL_LOGS", "false")
            buildConfigField("boolean", "DEBUG_PROTOCOL_WORKBENCH", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = stagedNativeRoot.get().file("CMakeLists.txt").asFile
            buildStagingDirectory = file(nativeBuildStagingDirProvider.get())
        }
    }

    androidResources {
        localeFilters += listOf("en", "ko")
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/DebugProbesKt.bin"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    composeCompiler {
        reportsDestination = project.layout.buildDirectory.dir("compose_compiler")
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("stability_config.conf"))
    }

    lint {
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    val okHttpBom = platform("com.squareup.okhttp3:okhttp-bom:5.3.2")

    implementation(composeBom)
    implementation(okHttpBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:19.1.0")
    implementation("com.google.maps.android:maps-compose:6.4.4")
    implementation("com.squareup.okhttp3:okhttp")

    val cameraxVersion = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("testClasses") {
    group = "verification"
    description = "Compatibility alias for IDEs that still request :app:testClasses on Android modules."
    dependsOn("compileDebugUnitTestSources")
}
