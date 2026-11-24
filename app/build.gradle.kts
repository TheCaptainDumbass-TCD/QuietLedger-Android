import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Short commit of the working tree, or "unknown" outside a checkout.
 *
 * Read at configuration time and baked in, so an installed build can be
 * identified from the About screen without hashing the APK.
 */
fun gitCommit(): String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

/** "clean", or "modified" when the build includes uncommitted work. */
fun gitState(): String = runCatching {
    val changes = providers.exec {
        commandLine("git", "status", "--porcelain")
    }.standardOutput.asText.get().trim()
    if (changes.isEmpty()) "clean" else "modified"
}.getOrDefault("unknown")


/**
 * Release signing details, read from keystore.properties at the project root.
 *
 * That file and the keystore itself are deliberately outside version control.
 * Losing them matters: without Play App Signing, a lost key means a published
 * app can never be updated again. Both are listed in the README under backup.
 *
 * Absent the file, release builds still assemble, just unsigned, so a fresh
 * clone is not blocked from building.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasSigningConfig = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.quietwire.quietledger"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.quietwire.quietledger"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Build provenance, so a running app can say exactly which commit it
        // came from. Debugging on a device meant guessing which APK was
        // actually installed, which is a bad way to start an investigation.
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        buildConfigField("String", "GIT_STATE", "\"${gitState()}\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        // Deliberately removed. This was a literal that said 6 while the
        // database was on 8, on a screen whose entire purpose is to report
        // what is running. About now reads the version from the open database,
        // which cannot drift.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // V1 is not set, because AGP ignores it once minSdk is 24 or
                // above and this app targets 29. V3 is on because it supports
                // signing key rotation, which is worth having available on an
                // app intended to be updated for years.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Keep the mapping file. Without it a stack trace from a release
            // build is unreadable, which matters the first time something
            // crashes on a device that is not in this room.
            isDebuggable = false
        }
        debug {
            // Deliberately no applicationIdSuffix. It would be better practice,
            // letting debug and release sit side by side, but it renames the
            // package, so the existing install and its data would be orphaned.
            // Worth doing at the point of a real release, not mid-testing.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Deliberate decisions live in app/lint.xml, each with a reason.
        // abortOnError keeps a security finding from being merely noted, and
        // checkDependencies extends the analysis into library manifests, which
        // is where surprises like an exported receiver come from.
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = false
        sarifReport = true
        htmlReport = true
        xmlReport = true
    }

    buildFeatures {
        compose = true
        // Needed for BuildConfig.DEBUG, which gates screenshot blocking so
        // debug builds can be captured for design feedback.
        buildConfig = true
    }

    // The broker export in samples/ is the fixture the importer is tested
    // against. Wiring the directory in avoids keeping a second copy in sync.
    sourceSets {
        // Room's exported schemas, so migration tests can build an old database
        // from Room's own definition rather than a hand-copied one that drifts.
        getByName("test") {
            resources.srcDirs("schemas")
        }

        named("test") {
            resources.directories.add("../samples")
            // Room's exported schemas, so MigrationTestHelper can load the old
            // version and validate the new one against it.
            assets.directories.add("schemas")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Exported schemas are checked in. They are what makes it possible to write
// real migration tests later instead of hoping an upgrade works.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Room talks to SQLCipher through the standard SupportSQLite interfaces,
    // so the database is encrypted at rest with no change to the DAOs.
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)

    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.serialization.json)
    // QR generation only, for authenticator enrolment. Pure Java, no Android deps.
    implementation(libs.zxing.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)

    // Room's migration helper, for the instrumented migration test. Was only
    // on the JVM test classpath, which meant that test never compiled and so
    // never ran, while being cited as device coverage.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
