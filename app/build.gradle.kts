plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

val versionPropsFile = file("version.properties")

fun loadVersionProps(): Properties {
    val props = Properties()
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { props.load(it) }
    } else {
        props["VERSION_CODE"] = "51"
        props["VERSION_NAME"] = "2.0.1"
        props["VERSION_MAJOR"] = "2"
        props["VERSION_MINOR"] = "0"
        props["VERSION_PATCH"] = "1"
        props["BUILD_COUNTER"] = "1"
        versionPropsFile.outputStream().use { props.store(it, "Version Properties") }
    }
    return props
}

val versionProps = loadVersionProps()
val currentVersionCode = (versionProps["VERSION_CODE"] as? String)?.toIntOrNull() ?: 51
val currentVersionName = (versionProps["VERSION_NAME"] as? String) ?: "2.0.1"

android {
    namespace = "com.android5.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.android5.ai"
        minSdk = 24
        targetSdk = 37
        versionCode = currentVersionCode
        versionName = currentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val buildDate = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(properties["RELEASE_STORE_FILE"] as String)
            storePassword = properties["RELEASE_STORE_PASSWORD"] as String
            keyAlias = properties["RELEASE_KEY_ALIAS"] as String
            keyPassword = properties["RELEASE_KEY_PASSWORD"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val bumpAppVersionsTask = tasks.register("BumpAppVersions") {
    description = "Increments versionCode and updates versionName in version.properties on every build"
    group = "versioning"
    doLast {
        val props = loadVersionProps()
        var code = (props["VERSION_CODE"] as? String)?.toIntOrNull() ?: 52
        val major = (props["VERSION_MAJOR"] as? String)?.toIntOrNull() ?: 2
        val minor = (props["VERSION_MINOR"] as? String)?.toIntOrNull() ?: 0
        var patch = (props["VERSION_PATCH"] as? String)?.toIntOrNull() ?: 2
        var buildCounter = (props["BUILD_COUNTER"] as? String)?.toIntOrNull() ?: 0

        code += 1
        buildCounter += 1

        if (code % 20 == 0 || buildCounter >= 20) {
            patch += 1
            buildCounter = 1
        }

        val newVersionName = "$major.$minor.$patch"
        props["VERSION_CODE"] = code.toString()
        props["VERSION_NAME"] = newVersionName
        props["VERSION_MAJOR"] = major.toString()
        props["VERSION_MINOR"] = minor.toString()
        props["VERSION_PATCH"] = patch.toString()
        props["BUILD_COUNTER"] = buildCounter.toString()

        versionPropsFile.outputStream().use {
            props.store(it, "Auto-generated and bumped Version Properties")
        }

        println("==================================================")
        println("  BumpAppVersions: Version Bumped Successfully!")
        println("  Version Code: $code")
        println("  Version Name: $newVersionName (Major: $major, Minor: $minor, Patch: $patch)")
        println("==================================================")
    }
}

// Run BumpAppVersions on every build
tasks.named("preBuild") {
    dependsOn(bumpAppVersionsTask)
}

tasks.register("bumpVersion") {
    description = "Alias for BumpAppVersions"
    group = "versioning"
    dependsOn(bumpAppVersionsTask)
}

tasks.register("bumpPatch") {
    description = "Bumps patch version (e.g. 2.0.1 -> 2.0.2) and increments versionCode"
    group = "versioning"
    doLast {
        val props = loadVersionProps()
        var code = (props["VERSION_CODE"] as? String)?.toIntOrNull() ?: 50
        val major = (props["VERSION_MAJOR"] as? String)?.toIntOrNull() ?: 2
        val minor = (props["VERSION_MINOR"] as? String)?.toIntOrNull() ?: 0
        var patch = (props["VERSION_PATCH"] as? String)?.toIntOrNull() ?: 0

        code += 1
        patch += 1
        val newVersionName = "$major.$minor.$patch"
        props["VERSION_CODE"] = code.toString()
        props["VERSION_NAME"] = newVersionName
        props["VERSION_PATCH"] = patch.toString()
        props["BUILD_COUNTER"] = "1"
        versionPropsFile.outputStream().use { props.store(it, "Version Properties") }
        println("Bumped to $newVersionName (Code: $code)")
    }
}

tasks.register("installAndPushApk") {
    description = "Builds the release APK, installs it on the connected device, and pushes a copy to the sdcard"
    group = "install"
    dependsOn("assembleRelease")

    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: error("No APK found in $apkDir")
        val apkPath = apk.absolutePath
        val devicePath = "/sdcard/${apk.name}"

        println("Installing APK: $apkPath")
        ProcessBuilder("adb", "install", "-r", apkPath).inheritIO().start().waitFor()

        println("Pushing APK to sdcard: $devicePath")
        ProcessBuilder("adb", "push", apkPath, devicePath).inheritIO().start().waitFor()

        println("Done. APK installed and pushed to $devicePath")
    }
}

tasks.register("UpdateMirrors") {
    description = "Updates mirror.json with the latest version info and pushes changes to the update-mirror repository"
    group = "publishing"

    doLast {
        val props = loadVersionProps()
        val verCode = (props["VERSION_CODE"] as? String)?.toIntOrNull() ?: 58
        val verName = (props["VERSION_NAME"] as? String) ?: "2.0.2"

        val mirrorDir = File(System.getProperty("user.home"), "update-mirror")
        if (!mirrorDir.exists()) {
            println("UpdateMirrors: Directory $mirrorDir does not exist, skipping mirror update.")
            return@doLast
        }

        val mirrorFile = File(mirrorDir, "mirror.json")
        val jsonContent = """
{
  "appPackageName": "com.android5.ai",
  "appLatestVersion": "$verName",
  "appLatestCodeVersion": $verCode,
  "googleIcon": "ic_provider_gemini.png",
  "openaiIcon": "ic_provider_openai.png",
  "openrouterIcon": "ic_provider_openrouter.png",
  "groqIcon": "ic_provider_groq.png",
  "updateStatus": "Latest release v$verName (Build $verCode)",
  "updateRequired": false,
  "updateLinkAvailable": true,
  "downloadUrl": "https://github.com/lunadevph/AIChat/releases/latest"
}
""".trimIndent() + "\n"

        mirrorFile.writeText(jsonContent)
        println("UpdateMirrors: Updated mirror.json to version $verName (code $verCode)")

        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val shell = if (isWindows) listOf("cmd.exe", "/c") else listOf("sh", "-c")
            val gitCmd = "git pull --rebase origin main && git add mirror.json && git commit -m \"Update mirror to v$verName (code $verCode)\" && git push origin main"

            val process = ProcessBuilder(shell + listOf(gitCmd))
                .directory(mirrorDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                println("UpdateMirrors: Successfully pushed update mirror to GitHub!")
            } else {
                println("UpdateMirrors git output:\n$output")
            }
        } catch (e: Exception) {
            println("UpdateMirrors: Error pushing mirror: ${e.message}")
        }
    }
}

// Automatically trigger UpdateMirrors when assembleRelease finishes
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("UpdateMirrors")
}

tasks.register("printVersion") {
    description = "Prints current app version name and version code"
    group = "versioning"
    doLast {
        val props = loadVersionProps()
        println("App Version: ${props["VERSION_NAME"]} (Code: ${props["VERSION_CODE"]})")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.android.material)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.mikepenz.markdown)
    implementation(libs.mikepenz.markdown.code)
    implementation(libs.mikepenz.markdown.coil)
    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
