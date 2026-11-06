import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.assistant.android.application)
    alias(libs.plugins.assistant.android.hilt)
    alias(libs.plugins.assistant.android.room)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// ---------------------------------------------------------------------------
// Static checks (Requirement 3.5, 9.1, 9.2): detekt bans logging APIs outside security.log,
// ktlint keeps formatting consistent. Both run as part of `check`.
// ---------------------------------------------------------------------------

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/withSms/kotlin", "src/test/kotlin", "src/testWithSms/kotlin")
    parallel = true
}

// detekt 1.23 embeds Kotlin 2.0; keep its own classpath on that version.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("${File.separator}build${File.separator}") }
    }
}

// ---------------------------------------------------------------------------
// Placeholders and phase flags (gradle.properties)
// ---------------------------------------------------------------------------

val appId: String = providers.gradleProperty("app.id").orNull
    ?: error("Gradle property 'app.id' is missing from gradle.properties")

/** Phase flags in a fixed order; each becomes a FeatureFlags constant and a bool resource. */
val phaseNames = listOf("calendar", "money", "subscriptions", "ai", "email", "polish")
val phaseFlags: Map<String, Boolean> = phaseNames.associateWith { name ->
    providers.gradleProperty("phase.$name").orNull?.toBooleanStrict() ?: false
}

// ---------------------------------------------------------------------------
// Release signing from environment variables only (Requirements 10.4 to 10.7).
// Nothing secret is stored in the repository.
// ---------------------------------------------------------------------------

val signingEnvNames = listOf(
    "ASSISTANT_KEYSTORE_PATH",
    "ASSISTANT_KEYSTORE_PASSWORD",
    "ASSISTANT_KEY_ALIAS",
    "ASSISTANT_KEY_PASSWORD",
)
val signingValues: Map<String, String?> = signingEnvNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
}
val hasSigningValues = signingValues.values.all { it != null }

android {
    lint {
        // Hindi and Tamil cover the daily-use screens; other strings fall back to English on purpose.
        warning += "MissingTranslation"
    }
    namespace = "app.assistant"

    defaultConfig {
        applicationId = appId
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // @bool/phase_* resources gate manifest components of each phase (android:enabled).
        phaseFlags.forEach { (name, enabled) ->
            resValue("bool", "phase_$name", enabled.toString())
        }
    }

    flavorDimensions += listOf("audience", "sms")
    productFlavors {
        // Developer's own build: Cloud AI key entered on device, no Consent_Screen (Req 45.1).
        create("personal") {
            dimension = "audience"
            buildConfigField("boolean", "SHARED_BUILD", "false")
        }
        // Build for other people: no AI key in the app, Proxy_Server, consent (Req 45.2, 66).
        create("shared") {
            dimension = "audience"
            buildConfigField("boolean", "SHARED_BUILD", "true")
        }
        // Adds the Sms_Reader and the SMS permissions from src/withSms/.
        create("withSms") {
            dimension = "sms"
            buildConfigField("boolean", "SMS_ENABLED", "true")
        }
        // Sms_Free_Build: no SMS code and no SMS permission (Req 67.2 to 67.4).
        create("smsFree") {
            dimension = "sms"
            buildConfigField("boolean", "SMS_ENABLED", "false")
        }
    }

    signingConfigs {
        if (hasSigningValues) {
            create("release") {
                storeFile = file(signingValues.getValue("ASSISTANT_KEYSTORE_PATH")!!)
                storePassword = signingValues.getValue("ASSISTANT_KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("ASSISTANT_KEY_ALIAS")
                keyPassword = signingValues.getValue("ASSISTANT_KEY_PASSWORD")
            }
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs merged resources and the manifest.
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
            all { test ->
                test.useJUnitPlatform()
                test.maxHeapSize = "2g"
                test.systemProperty("robolectric.logging", "stdout")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only set when every signing value is present; otherwise the
            // verifyReleaseSigning task below stops the build first.
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

// Exported Room schemas (table structure only, no data) are debug assets, so MigrationTestHelper
// can read them under Robolectric, which uses the tested variant's merged assets. They are not
// part of release builds.
android.sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

// ---------------------------------------------------------------------------
// Proto DataStore (settings). Lite runtime only.
// ---------------------------------------------------------------------------

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}

// KSP (Hilt) must see the generated proto classes; register them as variant sources
// (same workaround as Now in Android's datastore-proto module).
extensions.getByType<ApplicationAndroidComponentsExtension>().beforeVariants { variant ->
    android.sourceSets.register(variant.name) {
        val buildDir = layout.buildDirectory.get().asFile
        java.srcDir(buildDir.resolve("generated/source/proto/${variant.name}/java"))
        kotlin.srcDir(buildDir.resolve("generated/source/proto/${variant.name}/kotlin"))
    }
}

// ---------------------------------------------------------------------------
// FeatureFlags: a generated Kotlin object with one compile-time constant per phase.
// ---------------------------------------------------------------------------

abstract class GenerateFeatureFlagsTask : DefaultTask() {
    @get:Input
    abstract val flags: MapProperty<String, Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("app/assistant/app")
        dir.mkdirs()
        val constants = flags.get().entries.joinToString("\n") { (name, enabled) ->
            "    const val ${name.uppercase()}: Boolean = $enabled"
        }
        dir.resolve("FeatureFlags.kt").writeText(
            """
            |// Generated from the phase.* Gradle properties. Do not edit.
            |package app.assistant.app
            |
            |/** Compile-time phase toggles. A disabled phase has no entry points, receivers or workers. */
            |object FeatureFlags {
            |$constants
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateFeatureFlags = tasks.register<GenerateFeatureFlagsTask>("generateFeatureFlags") {
    flags.set(phaseFlags)
}

// Registered as a Java source directory: AGP 8 passes generated Java source directories to the
// Kotlin compiler, which compiles the .kt file there. `sources.kotlin` is not wired to KGP on AGP 8.
extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(
        generateFeatureFlags,
        GenerateFeatureFlagsTask::outputDir,
    )
}

// ---------------------------------------------------------------------------
// Release guard: fail before any release artifact is produced when signing values are missing
// (Requirement 10.5: no unsigned or debug-signed Release_Build).
// ---------------------------------------------------------------------------

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails when any release signing environment variable is missing."
    val missing = signingValues.filterValues { it == null }.keys.toList()
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing environment variables: " +
                    missing.joinToString(", ") +
                    ". Release builds are only produced by CI with secrets from the Secret_Store.",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Release verification (Requirements 2.1, 65.4, 67.2). Logic lives in build-logic ReleaseChecks.
// ---------------------------------------------------------------------------

abstract class VerifyManifestPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val smsFree: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @TaskAction
    fun verify() {
        val xml = manifest.get().asFile.readText()
        val found = app.assistant.buildlogic.ReleaseChecks.forbiddenPermissions(xml, smsFree.get())
        if (found.isNotEmpty()) {
            throw GradleException("${variantName.get()} declares forbidden permissions: ${found.sorted().joinToString()}")
        }
    }
}

val verifyReadOnlyPermissions = tasks.register("verifyReadOnlyPermissions") {
    group = "verification"
    description = "Fails if a release variant's merged manifest declares a forbidden permission."
}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants(
    extensions.getByType<ApplicationAndroidComponentsExtension>().selector().withBuildType("release"),
) { variant ->
    val check = tasks.register<VerifyManifestPermissionsTask>("verify${variant.name.replaceFirstChar { it.uppercase() }}Permissions") {
        manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
        smsFree.set(variant.productFlavors.any { it.second == "smsFree" })
        variantName.set(variant.name)
    }
    verifyReadOnlyPermissions.configure { dependsOn(check) }
}

val verifyVersionCode = tasks.register("verifyVersionCode") {
    group = "verification"
    description = "Fails unless versionCode is greater than release/last-version-code.txt."
    val code = android.defaultConfig.versionCode ?: 0
    val lastFile = rootProject.file("release/last-version-code.txt")
    inputs.property("versionCode", code)
    doLast {
        val last = if (lastFile.exists()) lastFile.readText() else null
        app.assistant.buildlogic.ReleaseChecks.versionCodeProblem(code, last)?.let { throw GradleException(it) }
    }
}

tasks.configureEach {
    val isReleaseArtifactTask = name.endsWith("Release") &&
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package"))
    if (isReleaseArtifactTask) {
        dependsOn(verifyReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Read-only sleep and steps, only after the User connects Health Connect.
    implementation(libs.androidx.health.connect)
    implementation(libs.kotlinx.coroutines.android)

    // Encrypted_Store: Room on SQLCipher (Room comes from the room convention plugin).
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // Settings
    implementation(libs.datastore)
    implementation(libs.protobuf.kotlin.lite)

    // Network (egress allow-list); used by the AI and mail adapters in later phases.
    implementation(libs.okhttp)
    implementation(libs.gson) { exclude(group = "com.google.errorprone", module = "error_prone_annotations") }

    // Phase 5 AI: strict JSON schemas (kotlinx.serialization) and the vendor-neutral Cloud adapter (Retrofit).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // Phase 6 Email: Jakarta Mail (Angus) for the read-only IMAP Mail_Connector.
    implementation(libs.angus.mail)

    // Phase 7 encrypted export: Argon2id (Bouncy Castle) + Tink streaming AEAD.
    implementation(libs.tink.android)
    implementation(libs.bouncycastle.prov)

    // Background work: WorkManager with Hilt-injected workers (Phase 2 calendar-refresh onward).
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.compose.material.icons)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Unit tests: JUnit 5 for JVM tests, the Vintage engine runs Robolectric's JUnit 4 runner.
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.vintage)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.konsist)

    // Compose UI tests run under Robolectric in the JVM unit-test source set (no emulator).
    testImplementation(libs.compose.ui.test.junit4)
    // The test ComponentActivity must be in the merged DEBUG manifest for Robolectric to resolve it.
    debugImplementation(libs.compose.ui.test.manifest)

    // Instrumented tests (emulator or device only)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit4)
}
