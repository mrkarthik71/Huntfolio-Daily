import app.assistant.buildlogic.AndroidConfig
import app.assistant.buildlogic.configureKotlinAndroid
import app.assistant.buildlogic.libs
import app.assistant.buildlogic.pluginId
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Applies the Android application plugin, Kotlin and the Compose compiler, and sets the
 * SDK levels, Java version and Compose build feature shared by the app.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("android-application"))
                apply(libs.pluginId("kotlin-android"))
                apply(libs.pluginId("kotlin-compose"))
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                defaultConfig {
                    targetSdk = AndroidConfig.TARGET_SDK
                    vectorDrawables.useSupportLibrary = true
                }
                buildFeatures {
                    compose = true
                    buildConfig = true
                }
                packaging {
                    resources {
                        excludes += setOf(
                            "/META-INF/{AL2.0,LGPL2.1}",
                            "/META-INF/LICENSE*",
                            "/META-INF/NOTICE*",
                            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                        )
                    }
                }
                lint {
                    abortOnError = true
                    checkReleaseBuilds = true
                    warningsAsErrors = false
                    // Versions are pinned on purpose in libs.versions.toml (AGP 8 line); upgrades
                    // are deliberate changes, not lint findings.
                    disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
                }
            }
        }
    }
}
