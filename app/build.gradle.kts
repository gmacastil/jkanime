import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.macastil.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.macastil.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = providers.gradleProperty("VERSION_CODE")
            .orElse("1")
            .get()
            .toInt()
        versionName = providers.gradleProperty("VERSION_NAME")
            .get()

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
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.create("releaseSigning")
                signingConfig?.storeFile = file(keystorePath)
                signingConfig?.storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                signingConfig?.keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                signingConfig?.keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
        jvmTarget.set(JvmTarget.JVM_21)
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

    // Reproductor nativo para el m3u8 de los players propios del sitio
    // (Desu/Magi): el WebView de la TV decodifica el stream pero no compone
    // la superficie (audio sí, vídeo negro).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
