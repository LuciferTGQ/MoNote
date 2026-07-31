plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

val rendererDir = rootProject.layout.projectDirectory.dir("renderer").asFile
val defaultNpmCommand = if (
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
) {
    "npm.cmd"
} else {
    "npm"
}
val npm = providers.environmentVariable("NPM_CMD").orElse(defaultNpmCommand)
val npmCache = rootProject.layout.projectDirectory.dir(".tools/npm-cache").asFile
val generatedRendererRoot = layout.buildDirectory.dir("generated/rendererAssets")
val generatedRenderer = generatedRendererRoot.map { it.dir("renderer") }

val installRenderer by tasks.registering(Exec::class) {
    workingDir(rendererDir)
    commandLine(
        npm.get(),
        "--cache",
        npmCache.absolutePath,
        "ci",
        "--no-audit",
        "--no-fund",
    )
    inputs.files(
        rendererDir.resolve("package.json"),
        rendererDir.resolve("package-lock.json"),
    )
    outputs.dir(rendererDir.resolve("node_modules"))
}

val buildRenderer by tasks.registering(Exec::class) {
    dependsOn(installRenderer)
    workingDir(rendererDir)
    commandLine(npm.get(), "--cache", npmCache.absolutePath, "run", "build")
    inputs.dir(rendererDir.resolve("src"))
    inputs.files(
        rendererDir.resolve("package.json"),
        rendererDir.resolve("package-lock.json"),
        rendererDir.resolve("index.html"),
        rendererDir.resolve("tsconfig.json"),
        rendererDir.resolve("vite.config.ts"),
    )
    outputs.dir(rendererDir.resolve("dist"))
}

val syncRenderer by tasks.registering(Sync::class) {
    dependsOn(buildRenderer)
    from(rendererDir.resolve("dist"))
    into(generatedRenderer)
}

android {
    namespace = "app.monote.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.monote.mobile"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    sourceSets["main"].assets.srcDir(generatedRendererRoot)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.webkit)
    implementation(libs.datastore)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncRenderer)
}
