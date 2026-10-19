plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
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

// Lower-case hex SHA-256 of a file, so the mirror can advertise the exact bytes
// of the APK it serves and a new client can check the download it just made.
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// The moment the mirror was written, in UTC ISO 8601.
fun nowIso(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}

// Computes the version a release build should use: same calendar-versioning rule
// as BumpAppVersions, but without writing anything. It is called during
// configuration (so the new version is stamped into *this* build) and again by
// the BumpAppVersions task (so it is persisted). Both reads see the same file,
// so both produce the same value.
fun nextBump(): Triple<String, Int, String> {
    val props = loadVersionProps()
    val stamp = todayStamp()

    val storedStamp = props["VERSION_STAMP"] as? String
    val storedBuild = (props["BUILD_COUNTER"] as? String)?.toIntOrNull() ?: 0

    // Counter resets to 1 on a new day; within the same day it keeps climbing.
    val build = if (storedStamp == stamp && storedBuild > 0) storedBuild + 1 else 1
    return Triple(stamp, build, nameFor(stamp, build))
}

// Auto-bump on release builds unless -PnoBump is passed. Never bumps on debug builds.
val noBumpRequested = project.hasProperty("noBump")
val isReleaseBuildRequested = gradle.startParameter.taskNames.any { name ->
    name.contains("Release", ignoreCase = true)
}
val shouldBump = isReleaseBuildRequested && !noBumpRequested

val versionProps = loadVersionProps()
val currentVersion: Pair<Int, String> = if (shouldBump) {
    // AGP resolves versionName/versionCode at configuration time, so the bump must
    // be applied here -- a task-time-only bump would ship an APK still carrying the
    // previous version (the write only lands on the *next* build).
    val (stamp, build, name) = nextBump()
    codeFor(stamp, build) to name
} else {
    val code = (versionProps["VERSION_CODE"] as? String)?.toIntOrNull()
        ?: codeFor(todayStamp(), 1)
    val name = (versionProps["VERSION_NAME"] as? String)
        ?: nameFor(todayStamp(), 1)
    code to name
}
val currentVersionCode = currentVersion.first
val currentVersionName = currentVersion.second

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
        // Same computation the release build used to stamp this APK, so the
        // persisted version always matches the artifact that was built.
        val (stamp, build, name) = nextBump()

        props["VERSION_STAMP"] = stamp
        props["BUILD_COUNTER"] = build.toString()
        props["VERSION_CODE"] = codeFor(stamp, build).toString()
        props["VERSION_NAME"] = name

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

// Wire the persisting bump task into release builds (unless -PnoBump). The value
// stamped into this build was already resolved above (shouldBump).
// Usage: gradlew assembleRelease            (bumps and stamps this build)
//        gradlew assembleRelease -PnoBump   (skips the bump)
if (shouldBump) {
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
            var apkSha256 = ""
            var apkSizeBytes = 0L
            if (apkFile != null && apkFile.exists()) {
                val destApk = File(mirrorDir, "latest.apk")
                apkFile.copyTo(destApk, overwrite = true)
                apkSha256 = sha256Of(destApk)
                apkSizeBytes = destApk.length()
                println("UpdateMirrors: Copied release APK (${apkFile.name}) to ${destApk.absolutePath} (${destApk.length()} bytes, sha256 $apkSha256)")
            } else {
                println("UpdateMirrors: Warning - release APK not found in $apkDir")
            }

            // Mirror format v2.
            //
            // Every v1 field keeps its exact name, type and meaning, and the new
            // ones are purely additive, so a client that only knows v1 (any build
            // shipped before this one) still parses the file and still sees an
            // update. Gson ignores fields a client has no property for, and the
            // added properties all have defaults, so both directions are safe.
            val rawDownloadUrl = "https://raw.githubusercontent.com/lunadevph/update-mirror/main/latest.apk?v=$verCode"
            val mirrorFile = File(mirrorDir, "mirror.json")
            val jsonContent = """
{
  "schemaVersion": 2,
  "appPackageName": "com.android5.ai",
  "appLatestVersion": "$verName",
  "appLatestCodeVersion": $verCode,
  "googleIcon": "ic_provider_gemini.png",
  "openaiIcon": "ic_provider_openai.png",
  "openrouterIcon": "ic_provider_openrouter.png",
  "groqIcon": "ic_provider_groq.png",
  "nvidiaIcon": "ic_provider_nvidia.png",
  "updateStatus": "Latest release v$verName (Build $verCode)",
  "updateRequired": false,
  "minVersionCode": 0,
  "updateLinkAvailable": true,
  "downloadUrl": "$rawDownloadUrl",
  "releasedAt": "${nowIso()}",
  "apkSizeBytes": $apkSizeBytes,
  "apkSha256": "$apkSha256",
  "changelog": "$changelogJson"
}
""".trimIndent() + "\n"

            mirrorFile.writeText(jsonContent)
            println("UpdateMirrors: Updated mirror.json to version $verName (code $verCode)")

            // 3. Stage, commit, and push
            //
            // The mirror repo is separate from the app repo and the ambient
            // credential (a GitHub App token) does not always have write access to
            // it. A dedicated token can be supplied through MIRROR_TOKEN or
            // mirror_token.txt (gitignored); without one the push uses whatever
            // git is already configured with.
            val mirrorToken =
                System.getenv("MIRROR_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: rootProject.file("mirror_token.txt")
                        .takeIf { it.exists() }
                        ?.readText()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }

            val pushRef =
                if (mirrorToken != null) {
                    "https://x-access-token:$mirrorToken@github.com/lunadevph/update-mirror.git"
                } else {
                    "origin"
                }

            // `git commit` fails when nothing changed, which would stop the chain
            // before the push and strand commits that already exist locally (a
            // backlog from an earlier refused push), so commit only if staged.
            val commitPushCmd = "git add mirror.json latest.apk && (git diff --cached --quiet || git commit -m \"Update mirror and latest.apk to v$verName (code $verCode)\") && git push $pushRef main"
            val process = ProcessBuilder(shell + listOf(commitPushCmd))
                .directory(mirrorDir)
                .redirectErrorStream(true)
                .start()

            val rawOutput = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            // Never echo the token back into the build log.
            val output = mirrorToken?.let { rawOutput.replace(it, "***") } ?: rawOutput

            if (exitCode == 0) {
                println("UpdateMirrors: Successfully pushed update mirror and latest.apk to GitHub!")
            } else {
                println("UpdateMirrors git output:\n$output")
                if (mirrorToken == null) {
                    println(
                        "UpdateMirrors: the push was refused. If this token cannot write to " +
                            "lunadevph/update-mirror, put a token that can in mirror_token.txt " +
                            "or MIRROR_TOKEN, then push $mirrorDir manually."
                    )
                }
            }
        } catch (e: Exception) {
            println("UpdateMirrors: Error pushing mirror: ${e.message}")
        }
    }
}

// Bundle the release notes as an asset so the app can show them offline.
// app/changelog.txt stays the single source of truth (see AGENTS.md).
tasks.register<Copy>("copyChangelogToAssets") {
    from(file("changelog.txt"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "changelog.txt" }
}
tasks.named("preBuild") { dependsOn("copyChangelogToAssets") }

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
    implementation(libs.androidx.appcompat)
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
