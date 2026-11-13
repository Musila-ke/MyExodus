import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.google.gms.google-services")
    alias(libs.plugins.crashlytics)
}

android {
    namespace = "com.example.myexodus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myexodus"
        // Plan §3: API 26 floor (UsageStatsManager + modern background-execution both present).
        // §16/§314: confirm against target-user-base analytics before treating as final.
        minSdk = 26
        targetSdk = 36
        // Release 0 is the first build that carries a meaningful version: the server's
        // clientCompatibility minimum is compared against this versionCode (plan §8.10).
        versionCode = 31
        versionName = "1.1.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Account plan §8.3: the registered Web Application Client ID for Credential Manager.
        // Blank until the owner registers the OAuth client; the Account pane hides Google and
        // offers email/password instead (the guaranteed non-GMS path).
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")

        // Plan §13.2: the dedicated public releases-only repo, pinned at build time with
        // -Pmyexodus.updateRepository=owner/repo. Empty means this build has no update
        // channel and Settings says so instead of checking.
        val updateRepository = (findProperty("myexodus.updateRepository") as? String).orEmpty()
        buildConfigField(
            "String",
            "UPDATE_RELEASE_REPOSITORY",
            "\"${updateRepository.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    // Plan §13.1-3: the release identity lives outside the repository. Release builds fail
    // closed when the owner's signing properties are absent; debug builds are unaffected.
    signingConfigs {
        create("release") {
            val signingProperties = Properties().apply {
                val path = System.getenv("MYEXODUS_RELEASE_SIGNING_PROPERTIES")
                    ?: "${System.getProperty("user.home")}/.myexodus-release-key/signing.properties"
                val propertiesFile = file(path)
                if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
            }
            signingProperties.getProperty("storeFile")?.let {
                storeFile = file(it)
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Gate 2.5 coverage: AGP's `createDebugUnitTestCoverageReport` then emits the
            // JaCoCo XML SonarQube ingests (build/reports/coverage/test/debug/report.xml).
            enableUnitTestCoverage = true
            // Domain usage metering (§8 S1.3): debug runs ENFORCE so the engine's enforcement
            // path is on-device testable; the S1.3 DataStore override can lower it per-install.
            buildConfigField("String", "DOMAIN_USAGE_MODE", "\"ENFORCE\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
            // §8 S1.3: release ships OFF until rollout (§13); the override is ignored here.
            buildConfigField("String", "DOMAIN_USAGE_MODE", "\"OFF\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    // 2026-11-08 build-speed pass: the JVM suite is ~2,500 Robolectric tests and ran one fork
    // for 5+ minutes on this 4-core machine. Two forks halve wall time; each is a separate
    // JVM, so Robolectric sandboxes and statics stay isolated. Do not raise past 2 on 8 GB.
    tasks.withType<Test>().configureEach {
        maxParallelForks = 2
    }
    // ADR #208: the standalone lintDebug gate is authoritative; skip the redundant per-release pass.
    lint {
        checkReleaseBuilds = false
    }
    sourceSets {
        getByName("androidTest") {
            // MigrationTestHelper loads the exported schema JSONs from the test APK's assets
            // (verified: androidx.room3.testing.AndroidMigrationTestHelper.loadSchema reads
            // instrumentation.context.assets/<dbClass-qualified-name>/<version>.json).
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

// §8 S1.4/S1.6 — static checks: wall-clock confinement inside `domainusage`, and PSL asset
// checksum against its provenance record.
val domainUsageCiChecks by tasks.registering(Exec::class) {
    description = "Domain usage metering static checks (wall-clock confinement, PSL pinning)"
    group = "verification"
    commandLine("bash", rootDir.resolve("scripts/domainusage-ci-checks.sh").absolutePath)
}

tasks.named("check") {
    dependsOn(domainUsageCiChecks)
}

// Instrumented-run safety guard (2026-09-28): AGP's connected tasks install through UTP, which
// UNINSTALLS the app and the test APK when the run ends. The next install then gets a fresh
// app UID and an empty data directory — on a physical handset that is the user's live rules
// store, wrapped keys, permissions and Firebase identity. It happened: three connected runs
// against the X6532 on 2026-09-28 destroyed the owner's install. Connected runs are
// emulator-only; device legs go through `install -r` + `adb shell am instrument`, which never
// uninstall.
tasks.matching { it.name.startsWith("connected") && it.name.endsWith("AndroidTest") }.configureEach {
    doFirst {
        val serial = System.getenv("ANDROID_SERIAL").orEmpty()
        check(serial.startsWith("emulator-")) {
            "Refusing to run $name: connected instrumented runs uninstall the app afterwards, " +
                "destroying the data directory of whatever device they touch. " +
                "Set ANDROID_SERIAL=emulator-5554 (currently " +
                (if (serial.isEmpty()) "unset" else "'$serial'") + "), or install with " +
                "`install -r` and run `adb shell am instrument` for the physical device."
        }
    }
}

ksp {
    // Room schema export (§8 Step 1 / §12): MigrationTestHelper needs the versioned schema
    // JSONs. Room 3's compiler still reads the `room.schemaLocation` processor option
    // (verified against room3-compiler 3.0.3 class constants).
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The JVM guard tests read source files directly (DestructiveFixtureGuardTest scans
// src/androidTest to keep destructive resets off handsets). androidTest sources are not inputs
// of the unit-test tasks, so an edit there alone left the task UP-TO-DATE and the guard never
// ran — register them so a violating edit actually re-runs the suite.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/androidTest/java"))
        .withPropertyName("androidTestSources")
        .optional()
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    // Aligns kotlinx-serialization to Room 3.0.3's compile target (AndroidX ships the
    // schema/migration serializers precompiled against 1.8.1; 1.7.x throws AbstractMethodError).
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.androidx.sqlite)
    implementation(libs.zetetic.sqlcipher)
    ksp(libs.androidx.room.compiler)
    // KSP2 quirk: the default `ksp` configuration does not reach test source sets — Room's
    // processor must be registered on kspTest or test @Database classes get no _Impl.
    kspTest(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.config)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.zxcvbn)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.androidx.datastore.preferences)
    // Home-screen widget (V1 Req 36). Pinned per the widget spec §5 Decision 12′, re-verified
    // against the AndroidX release page 2026-09-16: 1.1.1 stable, 1.2.0-rc01 RC-only.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.tink.android)
    implementation(libs.petero.cbor)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.bcpkix.jdk18on)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    // App Check enforcement (task #21): instrumented tests that hit the real Firestore must be
    // able to force the debug provider on non-attesting devices — documented path, secret passed
    // via `am instrument -e firebaseAppCheckDebugSecret <token>` (AppCheckTestSupport seeds it).
    // Deliberately NOT firebase-appcheck-debug-testing: its registrar, once merged into the app
    // manifest (required for component discovery on the app classpath), makes every normal debug
    // run crash provider creation — its DebugSecretProvider reads InstrumentationRegistry
    // unguarded, and a non-instrumented process has no arguments bundle (found 2026-09-20).
    // The plain artifact's provider takes that secret provider as an OPTIONAL component instead,
    // so with -testing absent it falls back to a persisted, generated debug secret.
    androidTestImplementation(libs.firebase.appcheck.debug)
    debugImplementation(libs.firebase.appcheck.debug)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Step 13: the Partner tab's approve/reject signs a chain event via BiometricPrompt —
    // moved from debugImplementation (the harness no longer owns the only prompt).
    implementation(libs.androidx.biometric)
    // Release 1 (account plan §8.3): Google sign-in through Credential Manager.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}
