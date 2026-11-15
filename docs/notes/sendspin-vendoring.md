# Sendspin vendoring (M0 spike)

Nov 1, 2026. Result of the M0 spike that `docs/PLAN.md` asks for ("lists the module's build
plugins and dependencies and decides whether to turn it into a plain Kotlin module"). Everything
below was checked against source or proved in a scratch build (`/tmp/sendspin-spike`), unless it
says **inferred**. M3 still has to prove it plays on a phone; nothing here ran on a device.

## Decision

1. **Vendor `sendspin-kmp` from `music-assistant/mobile-app` at commit
   `d4a7256c3b6a8c8ef3d30f986d4ac90e4e9c86bc`** (2026-10-31). Its main sources
   (`src/commonMain`, `src/androidMain`), `consumer-rules.pro` and `api/` are byte-identical to
   the first published release, tag `sendspin-v0.1.0` (commit `7ef162da`, verified with
   `git diff --stat`); the pinned commit adds three Robolectric host tests on top. Use
   `scripts/vendor-sendspin.sh`; it pins this commit.
2. **Keep it a Kotlin Multiplatform module with only the Android target (option a).** The
   sources are copied verbatim, zero edits, so re-vendoring is a directory copy. Only
   `sendspin/build.gradle.kts` is ours.
3. **The plan's premise changed upstream.** The Android decoder and the AudioTrack sink are no
   longer in the app's `player/local/` folder. They are inside the module, in
   `src/androidMain/kotlin/io/music_assistant/sendspin/android/` (`AndroidDecoders.kt`, 286
   lines; `AudioTrackSink.kt`, 250 lines), with `SharedPreferencesSendspinKeyStore`,
   `connectivityOnline`, `sendspinAudioDispatcher` and `ElapsedRealtimeClock`. The official app's
   `composeApp/src/androidMain` has no `player/local/` any more (only `iosMain` has one). So one
   directory is the whole vendoring job.

### Why option (a), not a plain Android library (b)

| | (a) KMP, Android target only | (b) Plain Android library |
| --- | --- | --- |
| Edits to upstream sources | **0** | 2 on every re-vendor: drop `internal expect fun defaultX25519Backend` (`commonMain/.../noise/crypto/X25519Backend.kt:32`) and the `actual` modifier (`androidMain/.../noise/crypto/X25519Backend.android.kt:17`). Without them the build fails: `'expect' and 'actual' declarations can be used only in multiplatform projects` (proved). |
| Source layout | Upstream's (`src/commonMain`, `src/androidMain`, ...) | Merged or re-pointed source dirs |
| Plugins | `org.jetbrains.kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`; both ship in jars already on the build classpath (KGP 2.4.21, AGP 9.5.0) | `com.android.library` |
| AAR | 947,987 bytes | 944,256 bytes |
| Extra work at build time | None measured: `assemble` runs `compileAndroidMain` only (no metadata compile); no Kotlin/Native toolchain is downloaded | None |

Settled by Q2 (fewest elements, steps and rules: no patch step to remember on every update);
Q4 is a tie.

## What the spike proved

Scratch build `/tmp/sendspin-spike`: Gradle 9.8.2 wrapper, AGP 9.5.0, Kotlin 2.4.21,
compileSdk 37, minSdk 28, JDK 21 host, Amsel's `gradle.properties` (configuration cache on),
Amsel's version catalog plus the entries below, `FAIL_ON_PROJECT_REPOS`, and an Amsel-like root
build (ktlint on every module but `:sendspin`). The module was produced by running
`scripts/vendor-sendspin.sh` into that directory; the copy is byte-identical to upstream.

| Check | Command | Result |
| --- | --- | --- |
| Compile + AAR | `./gradlew :sendspin:assembleAndroidMain` | OK. **AAR 947,987 bytes** (`classes.jar` 1,041,703 bytes, 590 entries). One upstream deprecation warning (`AndroidDecoders.kt:170`), silenced by `suppressWarnings` in our build file. |
| Unit tests | `./gradlew :sendspin:testAndroidHostTest` | **327 tests in 38 classes, 0 failures** (13.2 s of test time): all of `commonTest` on the host JVM plus the Robolectric `androidHostTest` classes (`@Config(sdk = [34])`), with Amsel's Robolectric 4.17 and JUnit 4.13.2. |
| Build time | as above | First build 56 s (includes dependency download). Clean assemble + tests, cold daemon: 98 s. Warm daemon, `--rerun-tasks`: assemble 31 s (`compileAndroidMain` 23.5 s), tests 44 s. No-op: 3 s. |
| R8 consumer | `:consumer:assembleRelease` (app with the module + `ktor-client-okhttp`, `isMinifyEnabled`, `proguard-android-optimize.txt`) | OK, no missing-class errors; the module's `consumer-rules.pro` (`-dontwarn org.bouncycastle.**`) reaches R8 through the project dependency. |
| APK cost | consumer vs a baseline app with OkHttp + coroutines + serialization only | **+814,537 bytes** with `org/bouncycastle/**` excluded from packaging. **+2,045,102 bytes** without it: BouncyCastle's non-class resources (`org/bouncycastle/pqc/legacy/picnic/lowmc*.bin.properties`, 1.2 MB) survive R8. |
| ktlint | `:sendspin:ktlintCheck` with Amsel's ktlint 1.8.0 | 1,838 violations in vendored code (upstream uses detekt and its own style), so `:sendspin` must skip ktlint. |

Release dex by origin in the consumer (apkanalyzer with the R8 mapping): module 182.7 KB, Ktor
165.7 KB, Concentus 78.1 KB, kotlinx.serialization 70.4 KB and extra coroutines (both largely
already in Amsel), kotlinx-io 8.3 KB, cryptography-kotlin 7.1 KB, slf4j-api 6.1 KB, Kermit
1.5 KB, BouncyCastle 0 KB (R8 inlined the X25519 math; no `org.bouncycastle` class is left).

Task names differ from a normal Android library: the KMP Android library has one variant, so
there is no `assembleRelease`, `compileDebugKotlin`, `test` or `testDebugUnitTest` in
`:sendspin`. Use `:sendspin:assembleAndroidMain`, `:sendspin:compileAndroidMain` and
`:sendspin:testAndroidHostTest` (or `:sendspin:allTests`).

## Changes the lead makes in the repo

1. Run `scripts/vendor-sendspin.sh` once from the repo root (no arguments). It creates
   `sendspin/` with `src/{commonMain,androidMain,commonTest,androidHostTest}`,
   `consumer-rules.pro`, upstream `README.md` and `CHANGELOG.md`, `LICENSE` (Apache-2.0, from the
   upstream repo root; upstream has no `NOTICE` file at this commit), `VENDORED.md` (commit,
   date, upstream dependency versions, hash of the upstream build file) and, on first run only,
   `build.gradle.kts`.
2. `settings.gradle.kts`: `include(":sendspin")`.
3. Root `build.gradle.kts`: add both plugins with `apply false` (KGP is already on the root
   classpath through `kotlin.jvm`, so a subproject cannot request it with a version), and skip
   ktlint for the vendored module:

   ```kotlin
   plugins {
       // ...existing aliases...
       alias(libs.plugins.kotlin.multiplatform) apply false
       alias(libs.plugins.android.kotlin.multiplatform.library) apply false
   }

   subprojects {
       // The vendored module is a verbatim upstream copy: never linted or reformatted.
       if (path != ":sendspin") {
           apply(plugin = "org.jlleitschuh.gradle.ktlint")
           extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> { /* as today */ }
       }
       tasks.withType<Test>().configureEach { /* as today */ }
   }
   ```

4. `gradle/libs.versions.toml` (versions are upstream's at the pinned commit, except where Amsel
   already pins one):

   ```toml
   [versions]
   # Sendspin (vendored module, see sendspin/VENDORED.md)
   ktor = "3.5.2"
   kermit = "2.1.0"
   atomicfu = "0.33.0"
   cryptographyKotlin = "0.6.0"
   bouncycastle = "1.85.2"
   concentus = "1.0.2"

   [libraries]
   ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
   ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
   ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
   ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
   kermit = { module = "co.touchlab:kermit", version.ref = "kermit" }
   kotlinx-atomicfu = { module = "org.jetbrains.kotlinx:atomicfu", version.ref = "atomicfu" }
   cryptography-core = { module = "dev.whyoleg.cryptography:cryptography-core", version.ref = "cryptographyKotlin" }
   cryptography-provider-jdk = { module = "dev.whyoleg.cryptography:cryptography-provider-jdk", version.ref = "cryptographyKotlin" }
   bouncycastle-provider = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
   concentus = { module = "io.github.jaredmdobson:concentus", version.ref = "concentus" }
   kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

   [plugins]
   android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
   kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
   ```

   The script prints a `note:` for every upstream version that differs from these keys
   (`ktor`, `kermit`, `atomicfu`, `cryptographyKotlin`, `bouncycastle`, `concentus`).
5. `sendspin/build.gradle.kts` (the script writes exactly this when the file is missing; this
   is the file the spike built and tested):

   ```kotlin
   // Amsel's build file for the vendored Sendspin module. Everything else in this directory is a
   // verbatim copy of music-assistant/mobile-app's sendspin-kmp (see VENDORED.md): never edit it,
   // re-run scripts/vendor-sendspin.sh. Only this file is ours. Upstream builds the same sources as
   // Kotlin Multiplatform with Android and iOS targets; we keep the multiplatform plugin with the
   // Android target alone, so commonMain/androidMain and the one expect/actual compile unchanged.
   import org.jetbrains.kotlin.gradle.dsl.JvmTarget

   plugins {
       alias(libs.plugins.kotlin.multiplatform)
       alias(libs.plugins.kotlin.serialization)
       alias(libs.plugins.android.kotlin.multiplatform.library)
   }

   kotlin {
       explicitApi()
       // Warnings in vendored code cannot be fixed here (the sources are never edited).
       compilerOptions { suppressWarnings.set(true) }

       android {
           namespace = "io.music_assistant.sendspin"
           compileSdk = libs.versions.compileSdk.get().toInt()
           minSdk = libs.versions.minSdk.get().toInt()
           compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
           optimization {
               // -dontwarn for the BouncyCastle classes R8 strips; ships to :app through the AAR.
               consumerKeepRules.publish = true
               consumerKeepRules.file("consumer-rules.pro")
           }
           withHostTestBuilder {}
       }

       sourceSets {
           commonMain.dependencies {
               // Public API types: StateFlow, Flow, CoroutineDispatcher, HttpClient.
               api(libs.kotlinx.coroutines.core)
               api(libs.ktor.client.core)
               implementation(libs.ktor.client.websockets)
               // Upstream takes kotlinx-serialization-json through ktor-serialization-kotlinx-json;
               // the sources use only kotlinx.serialization, so we depend on it directly.
               implementation(libs.kotlinx.serialization.json)
               implementation(libs.kotlinx.atomicfu)
               implementation(libs.kermit)
               implementation(libs.cryptography.core)
           }
           androidMain.dependencies {
               implementation(libs.cryptography.provider.jdk)
               // X25519 below API 33 (lightweight RFC 7748 math only; R8 strips the rest).
               implementation(libs.bouncycastle.provider)
               // Opus decoding in AndroidDecoderFactory.
               implementation(libs.concentus)
           }
           commonTest.dependencies {
               implementation(libs.kotlin.test)
               implementation(libs.kotlinx.coroutines.test)
               implementation(libs.turbine)
               implementation(libs.ktor.client.mock)
           }
           getByName("androidHostTest").dependencies {
               implementation(libs.junit)
               implementation(libs.robolectric)
           }
       }
   }
   ```

   Differences from upstream's file, all deliberate: no iOS targets, no `abiValidation()` (its
   dump comes from the iOS klibs), no `languageVersion`/`apiVersion` 2.3 pin (that exists for
   external consumers on older Kotlin), no `maven-publish`, `kotlinx-serialization-json` instead
   of `ktor-serialization-kotlinx-json`, Amsel's catalog keys (`compileSdk`, `minSdk`), and
   `suppressWarnings`.
6. `:playback/build.gradle.kts`: `implementation(project(":sendspin"))` and
   `implementation(libs.ktor.client.okhttp)`. Keep every Sendspin type inside `:playback`
   (`LocalPlayer` builds the player from Amsel's `OkHttpClient` and the `:api` client), so `:app`
   and `AppGraph` never import `io.music_assistant.sendspin` or Ktor.
7. `:app/build.gradle.kts` packaging: add `"org/bouncycastle/**"` to the existing
   `resources.excludes` (`app/build.gradle.kts:55`). Without it the release APK grows by
   1.23 MB of BouncyCastle data files that X25519 never reads.
8. CI (`.github/workflows/ci.yml`, test step): add `:sendspin:testAndroidHostTest`; the current
   `testDebugUnitTest test` selectors match no task in `:sendspin`. The stop hook needs nothing:
   vendored code changes only when re-vendored.
9. `docs/dependencies.txt`: `./gradlew :app:updateDependencyAllowlist` once `:playback` depends
   on `:sendspin`. In the spike consumer it adds these 55 `group:artifact` lines (atomicfu,
   coroutines, serialization, OkHttp and okio are already listed):

   ```text
   co.touchlab:kermit                                   2.1.0
   co.touchlab:kermit-android
   co.touchlab:kermit-core
   co.touchlab:kermit-core-android
   dev.whyoleg.cryptography:cryptography-bigint         0.6.0
   dev.whyoleg.cryptography:cryptography-bigint-jvm
   dev.whyoleg.cryptography:cryptography-bom
   dev.whyoleg.cryptography:cryptography-core
   dev.whyoleg.cryptography:cryptography-core-jvm
   dev.whyoleg.cryptography:cryptography-provider-base
   dev.whyoleg.cryptography:cryptography-provider-base-jvm
   dev.whyoleg.cryptography:cryptography-provider-jdk
   dev.whyoleg.cryptography:cryptography-provider-jdk-jvm
   dev.whyoleg.cryptography:cryptography-random
   dev.whyoleg.cryptography:cryptography-random-jvm
   dev.whyoleg.cryptography:cryptography-serialization-asn1
   dev.whyoleg.cryptography:cryptography-serialization-asn1-jvm
   dev.whyoleg.cryptography:cryptography-serialization-asn1-modules
   dev.whyoleg.cryptography:cryptography-serialization-asn1-modules-jvm
   dev.whyoleg.cryptography:cryptography-serialization-pem
   dev.whyoleg.cryptography:cryptography-serialization-pem-jvm
   io.github.jaredmdobson:concentus                     1.0.2
   io.ktor:ktor-client-core                             3.5.2 (all io.ktor below)
   io.ktor:ktor-client-core-jvm
   io.ktor:ktor-client-okhttp
   io.ktor:ktor-client-okhttp-jvm
   io.ktor:ktor-client-websockets
   io.ktor:ktor-client-websockets-jvm
   io.ktor:ktor-events
   io.ktor:ktor-events-jvm
   io.ktor:ktor-http
   io.ktor:ktor-http-cio
   io.ktor:ktor-http-cio-jvm
   io.ktor:ktor-http-jvm
   io.ktor:ktor-io
   io.ktor:ktor-io-jvm
   io.ktor:ktor-network
   io.ktor:ktor-network-jvm
   io.ktor:ktor-serialization
   io.ktor:ktor-serialization-jvm
   io.ktor:ktor-sse
   io.ktor:ktor-sse-jvm
   io.ktor:ktor-utils
   io.ktor:ktor-utils-jvm
   io.ktor:ktor-websocket-serialization
   io.ktor:ktor-websocket-serialization-jvm
   io.ktor:ktor-websockets
   io.ktor:ktor-websockets-jvm
   org.bouncycastle:bcprov-jdk18on                      1.85.2
   org.jetbrains.kotlinx:kotlinx-coroutines-slf4j       1.11.0
   org.jetbrains.kotlinx:kotlinx-io-bytestring          0.9.1
   org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm
   org.jetbrains.kotlinx:kotlinx-io-core                0.9.1
   org.jetbrains.kotlinx:kotlinx-io-core-jvm
   org.slf4j:slf4j-api                                  2.0.18
   ```

   (Versions are shown for reference only; the allowlist file holds `group:artifact`.)
   Reason for the PR: "Vendored Sendspin module (plan: Local audio). Every entry is a
   transitive dependency of code we do not edit; see docs/notes/sendspin-vendoring.md."

## Dependencies

What the module pulls, what each is for, and whether it can go without editing upstream sources.

| Dependency (version) | Used by | Drop or replace? |
| --- | --- | --- |
| `kotlinx-coroutines-core` 1.11.0 (api) | everywhere; public API types | Already on the allowlist. Keep. |
| `kotlinx-serialization-json` 1.11.0 + serialization plugin | wire messages, trust store JSON | Already on the allowlist. **Replaces** upstream's `ktor-serialization-kotlinx-json`: no source imports `io.ktor.serialization` (the spike compiled and passed every test without it). Saves four allowlist entries (`ktor-serialization-kotlinx(-json)(-jvm)`). |
| `ktor-client-core` 3.5.2 (api) + `ktor-client-websockets` 3.5.2 | `SendspinDeps.httpClient: HttpClient` (`api/SendspinPlayer.kt:34`), `TransportConnector.ktor` (`transport/TransportConnector.kt:41-51`), `WebSocketTransport` | **Cannot be dropped** without editing upstream: `HttpClient` is in the public constructor. Brings ktor-http, -utils, -io, -events, -sse, -network, -http-cio, -websockets, -serialization, -websocket-serialization, kotlinx-io and slf4j-api (all transitive of `ktor-client-core-jvm`). |
| `ktor-client-okhttp` 3.5.2 (in `:playback`, not in the module) | the engine; the module ships none (README, "It brings no Ktor engine") | Required. Runs on Amsel's own OkHttp 5.5.1 (Gradle upgrades its 5.3.2 request; compile + R8 proved, runtime not). |
| `slf4j-api` 2.0.18, `kotlinx-coroutines-slf4j` 1.11.0 | Ktor's JVM logger | Keep: excluding them would leave Ktor's logger classes pointing at missing classes. With no SLF4J provider Ktor logs nothing (**inferred**: slf4j 2.x prints one "no providers" line to stderr). 6 KB after R8. |
| `kermit` 2.1.0 (kermit, kermit-core, -android) | `Logger.withTag(...)` in 14 files | **Cannot be dropped** without edits. 1.5 KB after R8. Configure it once in Amsel: `Logger.setMinSeverity(Severity.Warn)` in release builds. |
| `atomicfu` 0.33.0 | `SynchronizedObject`, `atomic` (`audio/JitterBuffer.kt`, `clock/ClockSync.kt`, `identity/TrustStore.kt`, `pairing/PairingHandler.kt`) | Already on the allowlist (Compose brings it). Runtime library only, no atomicfu compiler plugin (upstream applies none either). |
| `cryptography-core` + `cryptography-provider-jdk` 0.6.0 | Noise: ChaCha20-Poly1305, SHA-256, HMAC, CSPRNG (`noise/crypto/CryptographyKotlinNoiseCrypto.kt`) | **Cannot be dropped** without edits. Pre-1.0 but a release, not an alpha; upstream pins it deliberately. 7.1 KB after R8. Its JDK provider is found through `ServiceLoader`; R8 rewrote the service file consistently (checked in the APK), runtime not proven. |
| `bcprov-jdk18on` 1.85.2 | X25519 below API 33 (`androidMain/.../X25519Backend.android.kt`, lightweight `org.bouncycastle.math.ec.rfc7748.X25519`) | **Cannot be dropped**: Amsel's minSdk is 28 and the platform JCA has X25519 only from 33. 0 KB of classes after R8, but its resource files need the `:app` packaging exclude above. |
| `concentus` 1.0.2 | Opus decoding in `AndroidDecoderFactory` | Cannot be dropped without edits (the factory references it). 78 KB after R8. |
| Test only: `kotlin-test`, `kotlinx-coroutines-test`, `turbine`, `ktor-client-mock`, `junit`, `robolectric` | `commonTest`, `androidHostTest` | Not on the release classpath. |

Amsel's network stack stays OkHttp: the Ktor client is a thin engine over Amsel's own
`OkHttpClient` (same dispatcher and connection pool, see below).

## How Amsel calls it

All of this is the module's public API (`io.music_assistant.sendspin` and `.api`, plus the
Android ports in `.android`). Every other declaration is `internal`.

### Construction, once per process

```kotlin
// Inside :playback. Process lifetime: created lazily the first time "This phone" is chosen
// (plan: "Sendspin starts only when 'This phone' is the selected player"), never closed.
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val config = MutableStateFlow<LocalPlayerConfig?>(null)

val sendspin: SendspinPlayer = SendspinPlayer(
    config = config,
    deps = SendspinDeps(
        sink = AudioTrackSink(context, ElapsedRealtimeClock),
        decoders = AndroidDecoderFactory(),
        keyStore = SharedPreferencesSendspinKeyStore(context),
        httpClient = HttpClient(
            OkHttp.create {
                preconfigured = okHttpClient.newBuilder().pingInterval(5, TimeUnit.SECONDS).build()
            },
        ),
        online = connectivityOnline(context, scope),
        approvePairing = { token -> maApi.pairWebPlayer(token) }, // see Pairing
        audioDispatcher = sendspinAudioDispatcher(),
        deviceInfo = DeviceInfo(
            productName = "Mobile Application",
            manufacturer = "Amsel",
            softwareVersion = BuildConfig.VERSION_NAME,
        ),
        clock = ElapsedRealtimeClock,
    ),
    scope = scope,
)
```

The spike compiled this shape through R8 (`consumer/.../SpikeApplication.kt`). Notes:

- **Factory**: `fun SendspinPlayer(config: StateFlow<LocalPlayerConfig?>, deps: SendspinDeps,
  scope: CoroutineScope): SendspinPlayer` (`SendspinPlayerFactory.kt:16-21`). No start, no stop:
  `config.value = null` is the only stop; it runs for the life of `scope`.
- **HttpClient**: the module derives its own client with `httpClient.config { install(WebSockets)
  { pingInterval = 5.seconds } }` and closes only that derived client when an enabled lifetime
  ends (`transport/TransportConnector.kt:41-51`, `player/SendspinPlayerImpl.kt:173,208`).
  Verified in Ktor 3.5.2 source: `config()` shares the engine (`HttpClient.kt:1438-1445`), and
  `OkHttpEngine.close()` ends in `client.dispatcher.executorService.shutdown()` for every client
  it built (`OkHttpEngine.kt:50-58`); with `preconfigured` set, those clients share the
  preconfigured client's dispatcher (`OkHttpEngine.kt:150-153`). So:
  - Pass an engine Amsel creates (`HttpClient(OkHttp.create { ... })`): such a client has
    `manageEngine = false` (`HttpClient.kt:965`), so no `close()` anywhere can close the engine
    and shut down the shared OkHttp dispatcher. Never call `engine.close()`.
  - With the OkHttp engine, Ktor's own pinger is skipped (`WebSockets.kt:108-109` returns the
    OkHttp session as is) and pings come only from `OkHttpClient.pingIntervalMillis`
    (`OkHttpWebsocketSession.kt:34-35`). The module asks for 5 s pings; only
    `newBuilder().pingInterval(5, SECONDS)` delivers them. `newBuilder()` keeps the shared
    dispatcher, pool and TLS settings.
- **Scope and threads**: same as the official app (`LocalPlayerAdapter.kt`: `SupervisorJob() +
  Dispatchers.IO`); the key store does SharedPreferences reads on it. Audio runs on
  `sendspinAudioDispatcher()`: one new thread at `THREAD_PRIORITY_AUDIO` per call
  (`android/AudioThread.kt`), so call it once per process, as the official app does
  (`PlatformDispatchers.android.kt:10`). `hold()` is thread-safe (a `MutableStateFlow`). Media3
  must be touched on its application looper: collect `state`/`events` and update `LocalPlayer`
  (a `SimpleBasePlayer`) on `Dispatchers.Main`.
- **Clock**: the same `ElapsedRealtimeClock` for the sink and `SendspinDeps.clock` (module
  README; it ticks through deep sleep).
- **Key store**: `SharedPreferencesSendspinKeyStore` writes the identity and trust records to the
  private prefs file `io.music_assistant.sendspin.keys` (`SharedPreferencesSendspinKeyStore.kt:27`).
  Use it as is: the KDoc in `api/SendspinKeyStore.kt` argues the Sendspin keys do not need the
  Keystore, and Amsel's manifest already has `android:allowBackup="false"`. Deleting the file
  makes the phone a new player on the server.
- **Logging**: Kermit's global logger, Logcat by default. In release:
  `Logger.setMinSeverity(Severity.Warn)`.

### Config

```kotlin
config.value = LocalPlayerConfig(
    endpoint = endpoint,                         // keep the same instance while it is unchanged
    deviceName = deviceName,                      // shown as the player name in MA
    codecPreference = listOf(AudioCodec.FLAC, AudioCodec.OPUS),
    bitDepth = 16,
    bufferSeconds = 10,                           // official app default (SettingsRepository.kt:849)
    userDelayMs = 0,
)
```

`api/LocalPlayerConfig.kt:12-26`. A change to `endpoint`, `deviceName`, `codecPreference`,
`bitDepth` or `bufferSeconds` reconnects; `userDelayMs` applies live. No setting is exposed for
any of these (settings budget): FLAC first with Opus as the fallback is the module README's quick
start; the official app defaults to FLAC alone, 16-bit, 10 s. `deviceName`: **inferred
proposal**, the phone's own name (`Settings.Global.DEVICE_NAME`, falling back to `Build.MODEL`),
capped by the module at 64 UTF-8 bytes (`wire/Messages.kt:106`); check the read on API 37 in M3.

### Endpoint, URL and auth

- URL: `<ws url>/sendspin`, where `<ws url>` is the control socket's origin and base path without
  `/ws`, for example `ws://192.168.1.20:8095/sendspin` or `wss://music.example.com/sendspin`.
  The official app builds `"${connectionInfo.wsUrl}/sendspin"` (`LocalPlayerEndpoints.kt:120`;
  `ConnectionInfo.kt:13,18`: `wsUrl = "<ws|wss>://host:port<basePath>"`). The server route is
  `GET /sendspin` (`server/.../webserver/controller.py:401`), a proxy to the internal Sendspin
  server.
- `Endpoint.WebSocket(url, wire, authToken = <the MA long-lived token>)` (`LocalPlayerConfig.kt:37`).
  With a token, the module first sends `{"type":"auth","token":"<token>","client_id":"<player id>"}`
  and waits up to 10 s for `{"type":"auth_ok"}` (`transport/ProxyAuth.kt`, `wire/Messages.kt:25-29`;
  server side `controllers/webserver/sendspin_proxy.py:125-173` on dev, the same code on 2.10.8). The server then binds
  this player to every control-socket session that uses the same token
  (`set_sendspin_player_for_token`), which is what makes the phone visible to this user. So the
  Sendspin auth must use the very token the control socket uses. The module reconnects by itself
  with that first frame, so over plain http Amsel keeps its config through a control-socket
  reconnect only on the network where the control socket last passed the `server_id` check
  (`sendspinServer`); over TLS it keeps it anywhere.
- Wire version, from the control socket's server info (`schema_version`, `server_version`), as
  the official app's `sendspinWireFor` (`EncryptedSendspinSupport.kt`): `schema_version >= 96`
  → `WireVersion.SPEC_1_0`; else `server_version` `2.10.N` with `N >= 7` → `SPEC_1_0`; else
  `LEGACY_9`. A schema below 45 gets `Endpoint.Unsupported(...)`; both supported lines are far
  above it (2.10.8: 65; dev: 100). Rebuild the endpoint only when url, wire or token change.

### Pairing and player registration

- The MA player id of the phone is the module's `client_id`: the base64url (no padding, 43
  chars) X25519 public key from the key store (`identity/SendspinIdentity.kt:15`), reported as
  `PlayerState.Connected.playerId`. The server creates the player under that id
  (`providers/sendspin/provider.py:1309`). Persist it (the official app keeps
  `sendspinEffectivePlayerId`), so "This phone" can be matched in the player list and listed
  first even before Sendspin connects.
- `approvePairing(token)`: send MA command `sendspin/pair_web_player` with args
  `{"pairing_token": token}` over the control socket; throw if the reply carries `error_code`
  (official: `LocalPlayerAdapter.kt:247-256`). The module calls it when a session comes up
  unpaired and gives it 120 s (`connection/SilentPairing.kt:56`). Server side
  (`provider.py:951-1010`, registered with `required_scope=Scope.PLAYERS_CONTROL` at line 1109) it pairs only a client it treats as a built-in
  web/app player: `device_info.product_name` in {"Web Browser", "Web Player", "Mobile
  Application", "PWA"} or manufacturer "Music Assistant" (`providers/sendspin/player.py:2077-2087`;
  same rule in 2.10.8). Hence `productName = "Mobile Application"` with our own manufacturer,
  "Amsel": it passes the check without claiming to be the official app. Such a player is also
  private and hidden by default (`player.py:2102-2108`).
- After 5 rejections in a row the player goes to `PlayerState.Failed(Unauthorized)`
  (`ReconnectPolicy.kt:23`); the only retry is `config = null` then the config again.
- `PlayerEvent.ServerRefreshNeeded`: the server assigned or changed the player id; refetch the
  player list.

### State and events LocalPlayer maps

`state: StateFlow<PlayerState>` (`Disabled`, `Connecting`, `Connected(playerId, serverName,
clock, audio)`, `Reconnecting(...)`, `Failed(cause)`) and `events: Flow<PlayerEvent>`
(`api/PlayerEvent.kt`). As the official adapter does (`LocalPlayerAdapter.kt:258-315`):

| Event | LocalPlayer does |
| --- | --- |
| `PlaybackStarted` | Mark playing (this is the audible confirmation). |
| `PlaybackStopped(FocusLost)` | Remember "paused by interruption" if it was playing; local pause (below). |
| `PlaybackStopped(Starved \| SinkFailed)` | Local pause. |
| `PlaybackStopped(ServerEnded \| Cleared \| Disabled)` | Nothing. |
| `FocusRegained` | Play again only if paused by the interruption. |
| `StreamStartedWhileHeld(playerId)` | Send the pause again (the first one was lost). |
| `ServerRefreshNeeded` | Refetch players. |
| `Warning(code)` | Log only. |

A local pause is `sendspin.hold(true)` at once (silences the buffered audio before the server
round trip; `api/SendspinPlayer.kt:22`) plus MA `players/cmd/pause` for the phone's player id.
Every play, seek, next or previous, and any play request aimed at the phone, calls
`hold(false)` first. When the server reports the player not playing, release the hold.
Transport commands never go to the module: the control plane is the MA API.

## What LocalPlayer must add, and what it must not

The module already does this inside `AudioTrackSink`, per opened track
(`android/AudioTrackSink.kt`):

- **Audio focus**: requests `AUDIOFOCUS_GAIN` with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` (line 172),
  ducks to gain 0.2 on `LOSS_TRANSIENT_CAN_DUCK`, mutes and emits `FocusLost` on loss, abandons
  on close (line 237).
- **Becoming noisy**: registers `ACTION_AUDIO_BECOMING_NOISY` (line 174); unplugging ends the
  stream with no automatic resume (`SinkInterruption.outputGone`).
- **Calls**: on API 31+ also watches the audio mode, for Bluetooth and Android Auto routes that
  send no focus loss.

So LocalPlayer **must not** request audio focus or register its own noisy receiver
(**inferred**: a second focus request from the same app would take focus from the sink's own
request and trigger its loss handling). A `SimpleBasePlayer` does neither by itself, unlike
ExoPlayer.

LocalPlayer **must add**:

- **Wake lock and Wi-Fi lock** while the phone plays. The official app holds only a Wi-Fi lock
  (`WIFI_MODE_FULL_HIGH_PERF`, in its service, `MainMediaPlaybackService.kt:161-177`) and no wake
  lock at all (no `PowerManager` use in the repo). Use Media3's own managers from
  `media3-common` 1.11.1, already a dependency: `androidx.media3.common.util.WakeLockManager`
  (`PARTIAL_WAKE_LOCK`, not reference counted) and `WifiLockManager`
  (`WIFI_MODE_FULL_HIGH_PERF`, not reference counted), both `@UnstableApi` like
  `SimpleBasePlayer` itself. `setEnabled(true)` once, then `setStayAwake(playing)` where
  playing means the phone's MA player is playing or `Connected.audio.phase` is `Buffering` or
  `Playing`. Both need only `WAKE_LOCK`, which is on the allowlist.
- **Foreground service**: the `MediaLibraryService` goes foreground (type `mediaPlayback`)
  only while its player reports playing, so `LocalPlayer` must report
  `playWhenReady = true` and `STATE_READY` while Sendspin plays and drop them on a pause.
- **Volume**: the module ignores server volume commands (it advertises no supported commands).
  The sink plays on `STREAM_MUSIC`, so the phone's volume keys are the volume; report the device
  as local playback.
- **Picking "This phone"**: set the config, wait for `PlayerState.Connected`, then send the MA
  transfer to that `playerId` (**inferred**: the target player must exist on the server before
  `player_queues/transfer` or `play_media` can address it).
- **Idle network budget**: while connected the module sends clock probes every 9 s
  (`clock/ClockProbe.kt:58`) and the server pings every 25 s, so "no network after 60 s idle in
  the background" means `config = null` once the phone is not the selected speaker, or has been
  paused in the background for 60 s, in step with the control socket.
- **Permissions**: the module manifest adds only `INTERNET` and `ACCESS_NETWORK_STATE` (both
  allowed). On Android 17 the Sendspin socket to a LAN server needs the same
  `ACCESS_LOCAL_NETWORK` grant as the control socket.

## Re-vendoring

1. `scripts/vendor-sendspin.sh <commit or tag>` (for example the next `sendspin-v*` release tag;
   `git ls-remote --tags https://github.com/music-assistant/mobile-app.git 'sendspin-v*'`
   lists them). Update `PINNED_REF` in the script to the commit it reports.
2. Read its output: a `warning:` means upstream's build file changed (compare its dependency
   block with ours); a `note:` names a dependency version to update in the catalog.
3. `./gradlew :sendspin:testAndroidHostTest :app:assembleRelease :app:checkBudgets`.
4. One line in `docs/DECISIONS.md` with the new commit.

`sendspin/` is never edited by hand except `build.gradle.kts`. If a fix is ever needed in the
sources, it goes upstream first.

## Open points

- Nothing here ran on a phone. M3 proves playback, focus handling, the `ServiceLoader` lookup
  of the cryptography provider after R8, the unflagged `registerReceiver` for the noisy
  broadcast on API 37 (a protected system broadcast, so **inferred** fine), and two hours with
  the screen off.
- The server's web-player check is a heuristic with a TODO to replace it with an explicit flag
  in the spec (`player.py:2075-2076`); if it changes, silent pairing changes with it.
- The upstream module version at the pinned commit reads `0.2.0`, unreleased (its changelog
  section is empty); the shipping code equals `0.1.0`.
- `com.android.kotlin.multiplatform.library` built on AGP 9.5.0 with no warnings; upstream's
  build file calls its `optimization` block incubating.
