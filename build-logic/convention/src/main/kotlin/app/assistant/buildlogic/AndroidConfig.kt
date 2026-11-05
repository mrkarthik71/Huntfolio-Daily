package app.assistant.buildlogic

import org.gradle.api.JavaVersion

/** Android SDK levels shared by every Android module. */
object AndroidConfig {
    /** Android 8.0, the oldest version the app supports (Requirement 10.1). */
    const val MIN_SDK = 26

    /** Android 15, the newest version the app is tested against (Requirement 10.1). */
    const val TARGET_SDK = 35

    /**
     * Compile SDK is one level above the target because the current AndroidX releases
     * (core 1.17, activity 1.11, Compose 1.9) require compileSdk 36. Compiling against a
     * newer SDK does not change runtime behaviour, which is governed by TARGET_SDK.
     */
    const val COMPILE_SDK = 36

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
    const val JVM_TARGET = "17"
}
