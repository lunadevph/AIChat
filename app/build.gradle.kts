plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.net.URL
import java.net.HttpURLConnection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val versionPropsFile = file("version.properties")

// Calendar versioning: YY.MM.DD.build  (e.g. 261010.001)
// VERSION_CODE is derived as YYMMDD * 1000 + build so it strictly increases every
// build and sorts chronologically. YYMMDD tops out at 991231, giving a ceiling of
// 992230999 -- comfortably inside Int.MAX (2147483647).
val DAY_STAMP = DateTimeFormatter.ofPattern("yyMMdd")

fun todayStamp(): String = LocalDate.now().format(DAY_STAMP)

fun codeFor(stamp: String, build: Int): Int = stamp.toInt() * 1000 + build

fun nameFor(stamp: String, build: Int): String = "$stamp.%03d".format(build)

fun loadVersionProps(): Properties {
    val props = Properties()
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { props.load(it) }
    }
    return props
}

val versionProps = loadVersionProps()
val currentVersionCode = (versionProps["VERSION_CODE"] as? String)?.toIntOrNull()
    ?: codeFor(todayStamp(), 1)
val currentVersionName = (versionProps["VERSION_NAME"] as? String)
    ?: nameFor(todayStamp(), 1)

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
            val keystorePropsFile = rootProject.file("keystore.properties")
            val keystoreProps = Properties()
            if (keystorePropsFile.exists()) {
                keystorePropsFile.inputStream().use { keystoreProps.load(it) }
            }

            fun getProp(name: String): String? {
                return (keystoreProps[name] as? String) ?: (properties[name] as? String) ?: System.getenv(name)
            }

            val storeFilePath = getProp("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = getProp("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = getProp("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = getProp("RELEASE_KEY_PASSWORD") ?: ""
            }
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
    description = "Stamps today's date into version.properties and increments the per-day build counter"
    group = "versioning"
    doLast {
        val props = loadVersionProps()
        val stamp = todayStamp()

        val storedStamp = props["VERSION_STAMP"] as? String
        val storedBuild = (props["BUILD_COUNTER"] as? String)?.toIntOrNull() ?: 0

        // Counter resets to 1 on a new day; within the same day it keeps climbing.
        val build = if (storedStamp == stamp && storedBuild > 0) storedBuild + 1 else 1

        props["VERSION_STAMP"] = stamp
        props["BUILD_COUNTER"] = build.toString()
        props["VERSION_CODE"] = codeFor(stamp, build).toString()
        props["VERSION_NAME"] = nameFor(stamp, build)

        versionPropsFile.outputStream().use {
            props.store(it, "Auto-generated calendar version (YY.MM.DD.build)")
        }

        println("==================================================")
        println("  BumpAppVersions: Version Bumped Successfully!")
        println("  Version Name: ${props["VERSION_NAME"]}")
        println("  Version Code: ${props["VERSION_CODE"]}")
        println("==================================================")
    }
}

// Auto-bump on release builds unless -PnoBump is passed.
// Never bump on debug builds.
// Usage: gradlew assembleRelease -PnoBump (skips version bump)
val isReleaseBuildRequested = gradle.startParameter.taskNames.any { name ->
    name.contains("Release", ignoreCase = true)
}

if (isReleaseBuildRequested && !project.hasProperty("noBump")) {
    tasks.named("preBuild") {
        dependsOn(bumpAppVersionsTask)
    }
}

tasks.register("bumpVersion") {
    description = "Alias for BumpAppVersions"
    group = "versioning"
    dependsOn(bumpAppVersionsTask)
}

tasks.register("bumpPatch") {
    description = "Re-stamps today's date and resets the build counter to 001"
    group = "versioning"
    doLast {
        val props = loadVersionProps()
        val stamp = todayStamp()
        props["VERSION_STAMP"] = stamp
        props["BUILD_COUNTER"] = "1"
        props["VERSION_CODE"] = codeFor(stamp, 1).toString()
        props["VERSION_NAME"] = nameFor(stamp, 1)
        versionPropsFile.outputStream().use { props.store(it, "Version Properties") }
println("Bumped to ${props["VERSION_NAME"]} (Code: ${props["VERSION_CODE"]})")
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

        // Read changelog
        val changelogFile = file("changelog.txt")
        val changelog = if (changelogFile.exists()) changelogFile.readText().trim() else ""
        val changelogJson = changelog
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        val mirrorDir = File(System.getProperty("user.home"), "update-mirror")
        if (!mirrorDir.exists()) {
            println("UpdateMirrors: Directory $mirrorDir does not exist, skipping mirror update.")
            return@doLast
        }

        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val shell = if (isWindows) listOf("cmd.exe", "/c") else listOf("sh", "-c")

            // 1. Pull latest changes first before modifying files
            val pullCmd = "git pull --rebase origin main"
            ProcessBuilder(shell + listOf(pullCmd))
                .directory(mirrorDir)
                .redirectErrorStream(true)
                .start()
                .waitFor()

            // 2. Copy APK and update mirror.json
            val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val apkFile = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            if (apkFile != null && apkFile.exists()) {
                val destApk = File(mirrorDir, "latest.apk")
                apkFile.copyTo(destApk, overwrite = true)
                println("UpdateMirrors: Copied release APK (${apkFile.name}) to ${destApk.absolutePath} (${destApk.length()} bytes)")
            } else {
                println("UpdateMirrors: Warning - release APK not found in $apkDir")
            }

            val rawDownloadUrl = "https://raw.githubusercontent.com/lunadevph/update-mirror/main/latest.apk"
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
  "downloadUrl": "$rawDownloadUrl",
  "changelog": "$changelogJson"
}
""".trimIndent() + "\n"

            mirrorFile.writeText(jsonContent)
            println("UpdateMirrors: Updated mirror.json to version $verName (code $verCode)")

            // 3. Stage, commit, and push
            val commitPushCmd = "git add mirror.json latest.apk && git commit -m \"Update mirror and latest.apk to v$verName (code $verCode)\" && git push origin main"
            val process = ProcessBuilder(shell + listOf(commitPushCmd))
                .directory(mirrorDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                println("UpdateMirrors: Successfully pushed update mirror and latest.apk to GitHub!")
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

tasks.register("uploadGithubRelease") {
    description = "Creates a GitHub Release and uploads the release APK as an asset"
    group = "publishing"

    doLast {
        val props = loadVersionProps()
        val verCode = (props["VERSION_CODE"] as? String)?.toIntOrNull() ?: 0
        val verName = (props["VERSION_NAME"] as? String) ?: "0.0.0"
        val tagName = "v$verName"

        // Read changelog
        val changelogFile = file("changelog.txt")
        val changelogBody = if (changelogFile.exists()) changelogFile.readText().trim() else "Release $tagName"

        // Read GitHub token
        val tokenFile = rootProject.file("github_token.txt")
        val token = when {
            tokenFile.exists() -> tokenFile.readText().trim()
            System.getenv("GITHUB_TOKEN") != null -> System.getenv("GITHUB_TOKEN")
            else -> error("GitHub token not found. Create github_token.txt in project root or set GITHUB_TOKEN env var.")
        }

        val owner = "LunaDevPH"
        val repo = "AIChat"

        // Escape JSON
        val bodyJson = changelogBody
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")

        // 1. Create release via GitHub API
        println("Creating GitHub release $tagName ...")
        val createUrl = URL("https://api.github.com/repos/$owner/$repo/releases")
        val conn = createUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val createBody = """{"tag_name":"$tagName","name":"$tagName (Build $verCode)","body":"$bodyJson","draft":false,"prerelease":false}"""
        conn.outputStream.use { it.write(createBody.toByteArray()) }

        val responseCode = conn.responseCode
        val responseBody = (if (responseCode < 400) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()

        if (responseCode !in 200..201) {
            error("Failed to create release (HTTP $responseCode): $responseBody")
        }

        // Extract upload_url from response (simple regex since we can't depend on Gson here at task level)
        val uploadUrlMatch = Regex("\"upload_url\"\\s*:\\s*\"([^\"]+)\"").find(responseBody)
            ?: error("Could not find upload_url in response: $responseBody")
        val uploadUrlTemplate = uploadUrlMatch.groupValues[1].replace("{?name,label}", "")

        println("Release created! Uploading APK ...")

        // 2. Upload APK asset
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apkFile = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: error("No release APK found in $apkDir. Run assembleRelease first.")

        val uploadUrl = URL("$uploadUrlTemplate?name=AIChat-v$verName.apk")
        val uploadConn = uploadUrl.openConnection() as HttpURLConnection
        uploadConn.requestMethod = "POST"
        uploadConn.setRequestProperty("Authorization", "Bearer $token")
        uploadConn.setRequestProperty("Accept", "application/vnd.github+json")
        uploadConn.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
        uploadConn.doOutput = true
        uploadConn.setFixedLengthStreamingMode(apkFile.length())

        apkFile.inputStream().use { input ->
            uploadConn.outputStream.use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }

        val uploadResponseCode = uploadConn.responseCode
        if (uploadResponseCode !in 200..201) {
            val uploadError = uploadConn.errorStream?.bufferedReader()?.readText() ?: "unknown"
            error("Failed to upload APK (HTTP $uploadResponseCode): $uploadError")
        }

        println("=================================================")
        println("  GitHub Release published successfully!")
        println("  Tag: $tagName")
        println("  APK: AIChat-v$verName.apk (${apkFile.length() / 1024 / 1024} MB)")
        println("  URL: https://github.com/$owner/$repo/releases/tag/$tagName")
        println("=================================================")
    }
}

// Convenience: build, push mirror, and create GitHub release in one go
// Usage: gradlew uploadRelease [-PnoBump]
tasks.register("uploadRelease") {
    description = "Builds release APK, updates mirror, and creates a GitHub Release"
    group = "publishing"
    dependsOn("assembleRelease")
    finalizedBy("uploadGithubRelease")
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
