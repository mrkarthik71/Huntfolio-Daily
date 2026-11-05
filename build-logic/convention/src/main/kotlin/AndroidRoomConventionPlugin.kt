import androidx.room.gradle.RoomExtension
import app.assistant.buildlogic.lib
import app.assistant.buildlogic.libs
import app.assistant.buildlogic.pluginId
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies Room with KSP and exports every schema version to `<module>/schemas`
 * (for the app: `app/schemas`). Exported schemas are committed and drive the
 * migration tests (design: hand-written migrations, no destructive fallback).
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("room"))
                apply(libs.pluginId("ksp"))
            }

            extensions.configure<KspExtension> {
                arg("room.generateKotlin", "true")
            }

            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                add("implementation", libs.lib("room-runtime"))
                add("implementation", libs.lib("room-ktx"))
                add("ksp", libs.lib("room-compiler"))
                add("testImplementation", libs.lib("room-testing"))
                add("androidTestImplementation", libs.lib("room-testing"))
            }
        }
    }
}
