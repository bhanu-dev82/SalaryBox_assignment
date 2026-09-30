plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bhanu.attendance.core.designsystem"
    compileSdk = 37

    defaultConfig { minSdk = 26 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.addAll(
                "androidx.compose.material3.ExperimentalMaterial3Api",
                "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
                "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            )
        }
    }
}

dependencies {
    // The design system is app-specific rather than a generic library: components like the
    // face-capture overlay render domain concepts (QualityIssue) and status colours map to
    // domain states. Depending on :domain one-way keeps :domain free of any UI knowledge.
    api(project(":domain"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.activity.compose)
    api(libs.lifecycle.runtime.compose)

    // The face-capture session is shared by admin enrolment and staff attendance, so it lives
    // in the shared UI module rather than being duplicated in both feature modules.
    api(project(":data"))
    implementation(project(":core:common"))

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.coroutines.android)

    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)
    implementation(libs.material3.adaptive.navigation.suite)
    implementation(libs.window)
    implementation(libs.core.ktx)
    implementation( libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
