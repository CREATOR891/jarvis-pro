plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

// Плоская раскладка: все файлы лежат в корне репозитория.
// Эта задача раскладывает их по нужным папкам перед сборкой.
val prepareFlat = tasks.register<Copy>("prepareFlat") {
    into("build/flat")
    from(fileTree(".") { include("*.kt") }) { into("java") }
    from(fileTree(".") { include("strings.xml") }) { into("res/values") }
    from(fileTree(".") { include("accessibility_config.xml") }) { into("res/xml") }
}

android {
    namespace = "com.jarvis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-beta"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDir("build/flat/java")
            res.srcDir("build/flat/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") { dependsOn(prepareFlat) }
tasks.configureEach {
    if (name.startsWith("merge") || name.startsWith("compile") || name.startsWith("generate")) {
        dependsOn(prepareFlat)
    }
}
