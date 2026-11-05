import app.assistant.buildlogic.configureKotlinJvm
import app.assistant.buildlogic.libs
import app.assistant.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Plain Kotlin/JVM library. Not used while the app is a single module; it is ready for the
 * Now in Android split into :core:model and :core:parse (design: Modularisation path).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(libs.pluginId("kotlin-jvm"))
            configureKotlinJvm()
        }
    }
}
