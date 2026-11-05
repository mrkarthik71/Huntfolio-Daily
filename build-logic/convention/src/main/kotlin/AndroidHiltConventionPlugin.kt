import app.assistant.buildlogic.lib
import app.assistant.buildlogic.libs
import app.assistant.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Applies Hilt with KSP code generation. */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply(libs.pluginId("ksp"))
                apply(libs.pluginId("hilt"))
            }

            dependencies {
                add("implementation", libs.lib("hilt-android"))
                add("ksp", libs.lib("hilt-compiler"))
            }
        }
    }
}
