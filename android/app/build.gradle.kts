plugins { id("com.android.application") }
android {
    namespace = "dev.eddux.clashcapture"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.eddux.clashcapture"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies { testImplementation("junit:junit:4.13.2") }
