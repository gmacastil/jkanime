import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "net.jkanime.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.jkanime.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Config del backend de sync. Se lee de gradle.properties o, mejor,
        // de local.properties (ignorado por git): así la clave del proyecto
        // no acaba versionada. La clave que debe ir aquí es la *publishable*
        // (`sb_publishable_...`), no la `sb_secret_...`: el secreto del
        // proyecto nunca debe viajar dentro del APK.
        val localProps = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        fun prop(name: String): String =
            providers.gradleProperty(name).orElse(localProps.getProperty(name) ?: "").get()
        val supabaseUrl = prop("JK_SUPABASE_URL")
        val supabaseAnonKey = prop("JK_SUPABASE_ANON_KEY")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:ui"))
    implementation(project(":data:catalog"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)

    debugImplementation(libs.compose.ui.tooling)
}
