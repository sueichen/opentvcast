/**
 * opentvcast JVM Test Runner
 *
 * Runs the whole protocol test suite as plain JUnit, WITHOUT the Android Gradle
 * Plugin. Two reasons this module exists:
 *
 * 1. **`internal` visibility.** Production and test sources are compiled as one
 *    Kotlin module, so tests can reach `internal` members. Once the protocol
 *    code moved into :airplay / :dlna, keeping the tests in :app broke that:
 *    `internal` is not visible across module boundaries, and six declarations
 *    in :airplay (RaopRsa, RtspRequestReader, TimingHandler, VideoDecoder,
 *    AirPlayReceiver) are internal.
 *
 * 2. **No Google Maven.** Everything resolves from Maven Central, so the suite
 *    runs in sandboxes where maven.google.com is unreachable.
 *
 * Usage: ./gradlew :test-runner:test
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin {
            // Production sources from every module plus the tests, in one
            // compilation unit. Order does not matter; duplicates would.
            //
            // LIMITATION, AND IT MATTERS: merging every module into a single
            // compilation unit is what lets these tests reach `internal` members,
            // but it also means this module CANNOT detect a broken module
            // boundary. A reference from :airplay to an :app-only class compiles
            // happily here and fails only under AGP — which is exactly how
            // :airplay's dependence on Logger/NetworkUtils stayed invisible until
            // the first real `assembleDebug`. Treat this task as a fast test gate,
            // not a structural check; the structural check is the AGP build.
            srcDirs(
                "../core/src/main/kotlin",
                "../platform/src/main/kotlin",
                "../airplay/src/main/kotlin",
                "../dlna/src/main/kotlin",
                "../app/src/main/kotlin",
                "../platform/src/test/kotlin",
                "../airplay/src/test/kotlin",
                "../dlna/src/test/kotlin",
                "../app/src/test/kotlin",
                "src/stubs"               // standalone replacements for Android-heavy files
            )

            // Files that genuinely need a device or a full Android runtime. Each
            // exclusion is a real limitation, not a convenience — see the reasons
            // below. Anything listed here is NOT covered by CI and must be run as
            // an instrumented test instead.
            exclude(
                // UI layer depends on AppCompat/Leanback/DataStore and the Activity lifecycle.
                "**/ui/**",
                "**/MainActivity.kt",
                "**/OpenTvCastApp.kt",
                "**/settings/SettingsRepository.kt",
                "**/service/CastService.kt",
                "**/service/BootReceiver.kt",

                // Framework static initialisers reach JNI that does not exist on a
                // desktop JVM. These three need Robolectric or a device, so they
                // run under :app / :platform instead. Excluding a test here means
                // ensuring it runs somewhere else — see docs/guides/PITFALLS.md.
                "**/MdnsServiceTest.kt",
                "**/ServiceControllerTest.kt",
                "**/NetworkUtilsTest.kt",

                // Robolectric tests added in Phase E (run under :app, which carries
                // the robolectric runtime). SettingsRepositoryTest needs a real
                // Context; BootReceiverTest references BootReceiver, which is
                // excluded above, so it cannot compile here either.
                "**/settings/SettingsRepositoryTest.kt",
                "**/service/BootReceiverTest.kt",

                // Robolectric runner — needs the robolectric runtime, which this
                // JVM-only module deliberately does not carry (android-all alone
                // is not Robolectric). Runs under :platform:testDebugUnitTest.
                // The pure half of the same coverage is plain JUnit and does run
                // here (AndroidNetworkMonitorTest).
                "**/platform/net/AndroidNetworkMonitorSmokeTest.kt",
                // Needs Robolectric (a View, a LayoutInflater) and the ui/** sources this
                // runner excludes. Runs under :app:testGoogletvDebugUnitTest.
                "**/ui/StreamingScreenTest.kt",

                // VideoDecoder is shadowed by src/stubs/VideoDecoder.kt, which drops
                // the MediaCodec/Surface wiring but keeps the companion members
                // (parseSpsResolution, SpsBitReader) that VideoDecoderSpsTest exercises.
                "**/airplay/VideoDecoder.kt"
            )
        }
    }
}

dependencies {
    // ── Android API definitions ───────────────────────────────────────────────
    // Robolectric's android-all JAR supplies MediaCodec, AudioTrack, NsdManager,
    // Build, etc. for both compilation and execution.
    compileOnly("org.robolectric:android-all:14-robolectric-10818077")
    runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")

    // ── Kotlin ────────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // ── Coroutines (:core exposes coroutines-flow through its api dependency) ─
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ── Bouncy Castle (Ed25519/X25519 pairing, AES-128-CTR mirror streams) ────
    // Note: bcprov-jdk15on was renamed to bcprov-jdk18on from 1.71 onward.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── dd-plist (binary plist for the AirPlay 2 handshake) ───────────────────
    implementation("com.googlecode.plist:dd-plist:1.28")

    // ── Timber ────────────────────────────────────────────────────────────────
    // Ships as an AAR on Maven Central, which we cannot unpack without AGP, so we
    // reference the extracted classes.jar. Without a planted Tree, Timber swallows
    // every log call and never touches android.util.Log.
    implementation(files("libs/timber-4.7.1-classes.jar"))

    // ── Test dependencies (main scope, because the sources are merged) ────────
    implementation("junit:junit:4.13.2")
    implementation("io.mockk:mockk:1.13.10")
}

tasks.test {
    // Test sources live in `main`, so the test task must scan the main output.
    testClassesDirs = sourceSets.main.get().output.classesDirs
    classpath = sourceSets.main.get().runtimeClasspath
    useJUnit()
    // MockK instruments bytecode at runtime; keep dynamic agent loading enabled.
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
