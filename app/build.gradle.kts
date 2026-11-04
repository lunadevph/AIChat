plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.util.Properties
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

val versionPropsFile = file("version.properties")

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

// Display versions are semantic; Android's independent code must never decrease.
data class AppVersion(val code: Int, val name: String)

fun readAppVersion(props: Properties): AppVersion {
    val code = props.getProperty("VERSION_CODE")?.toIntOrNull()
        ?: error("version.properties needs a valid VERSION_CODE")
    val name = props.getProperty("VERSION_NAME") ?: error("version.properties needs VERSION_NAME")
    require(code > 0 && Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(name)) {
        "Use a positive VERSION_CODE and a major.minor.patch VERSION_NAME"
    }
    return AppVersion(code, name)
}

fun incrementVersion(version: AppVersion, part: String): AppVersion {
    val (major, minor, patch) = version.name.split('.').map { it.toInt() }
    val nextName = when (part) {
        "major" -> "${Math.addExact(major, 1)}.0.0"
        "minor" -> "$major.${Math.addExact(minor, 1)}.0"
        "patch" -> "$major.$minor.${Math.addExact(patch, 1)}"
        else -> error("versionBump must be patch, minor or major")
    }
    return AppVersion(Math.addExact(version.code, 1), nextName)
}

val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val explicitBump = requestedTasks.any { it in setOf("bumpVersion", "bumpPatch", "BumpAppVersions") }
val noBumpRequested = project.hasProperty("noBump")
val isReleaseBuildRequested = requestedTasks.any {
    (it.contains("Release", ignoreCase = true) && it != "uploadGithubRelease") || it == "installAndPushApk"
}
val shouldBump = isReleaseBuildRequested && !noBumpRequested
val storedVersion = readAppVersion(loadVersionProps())
val bumpedVersion = incrementVersion(storedVersion, providers.gradleProperty("versionBump").getOrElse("patch"))
// Resolve once at configuration time and persist exactly these values before building.
val currentVersion = if (shouldBump || explicitBump) bumpedVersion else storedVersion
val currentVersionCode = currentVersion.code
val currentVersionName = currentVersion.name
val publishingChannel = providers.gradleProperty("updateChannel").getOrElse("release").lowercase(Locale.US)
require(publishingChannel in setOf("release", "debug")) { "updateChannel must be release or debug" }
val todayBuildNumber = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
val displayBuildNumber = if (shouldBump || explicitBump) todayBuildNumber else loadVersionProps().getProperty("BUILD_NUMBER", todayBuildNumber)
require(Regex("[0-9]{6}").matches(displayBuildNumber)) { "BUILD_NUMBER must contain six digits" }
val buildInfoDirectory = layout.buildDirectory.dir("generated/update-info/assets")

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
        buildConfigField("String", "BUILD_NUMBER", "\"$displayBuildNumber\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"$publishingChannel\"")
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
        getByName("debug") {
            buildConfigField("String", "UPDATE_CHANNEL", "\"debug\"")
        }
        release {
            isDebuggable = publishingChannel == "debug"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets.getByName("main").assets.srcDir(buildInfoDirectory.get().asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

val writeUpdateBuildInfo = tasks.register("writeUpdateBuildInfo") {
    inputs.property("channel", publishingChannel)
    inputs.property("versionCode", currentVersionCode)
    outputs.dir(buildInfoDirectory)
    doLast {
        val directory = buildInfoDirectory.get().asFile.apply { mkdirs() }
        File(directory, "release-channel.txt").writeText(publishingChannel)
        File(directory, "release-code.txt").writeText(currentVersionCode.toString())
    }
}
tasks.named("preBuild") { dependsOn(writeUpdateBuildInfo) }

val bumpAppVersionsTask = tasks.register("BumpAppVersions") {
    description = "Increments the semantic version and Android version code once"
    group = "versioning"
    dependsOn("checkChangelog")
    doLast {
        check(readAppVersion(loadVersionProps()) == storedVersion) {
            "version.properties changed during this build; retry to avoid a version mismatch"
        }
        val temporary = File(versionPropsFile.parentFile, "version.properties.tmp")
        temporary.writeText("VERSION_CODE=${bumpedVersion.code}\nVERSION_NAME=${bumpedVersion.name}\nBUILD_NUMBER=$displayBuildNumber\n")
        Files.move(temporary.toPath(), versionPropsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        println("Version: ${bumpedVersion.name} (code ${bumpedVersion.code})")
    }
}

if (shouldBump || explicitBump) {
    tasks.named("preBuild") { dependsOn(bumpAppVersionsTask) }
}

tasks.register("bumpVersion") {
    description = "Increment patch version; use -PversionBump=minor or major for a larger change"
    group = "versioning"
    dependsOn(bumpAppVersionsTask)
}

tasks.register("bumpPatch") {
    description = "Deprecated alias for bumpVersion; never resets the Android version code"
    group = "versioning"
    dependsOn(bumpAppVersionsTask)
}

// Read-only regression checks; no APK publishing or version file writes.
tasks.register("verifyVersioning") {
    group = "verification"
    doLast {
        check(incrementVersion(AppVersion(261019016, "1.0.0"), "patch") == AppVersion(261019017, "1.0.1"))
        check(incrementVersion(AppVersion(261019017, "1.2.9"), "minor") == AppVersion(261019018, "1.3.0"))
        check(incrementVersion(AppVersion(261019018, "1.2.9"), "major") == AppVersion(261019019, "2.0.0"))
        check(runCatching { incrementVersion(AppVersion(Int.MAX_VALUE, "1.0.0"), "patch") }.isFailure)
        check(currentVersion.code >= storedVersion.code)
        println("Versioning checks passed: ${currentVersion.name} (code ${currentVersion.code})")
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
    dependsOn("checkChangelog")

    doLast {
        val props = loadVersionProps()
        val publishedVersion = readAppVersion(props)
        val verCode = publishedVersion.code
        val verName = publishedVersion.name

        // Read changelog
        val changelogFile = file("changelog.txt")
        val changelog = if (changelogFile.exists()) changelogFile.readText().trim() else ""
        val changelogJson = changelog
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        val mirrorDir = File(System.getProperty("user.home"), "update-mirror")
        require(mirrorDir.isDirectory) { "Clone update-mirror to $mirrorDir before publishing" }
        val channelPrefix = if (publishingChannel == "debug") "debug/" else ""
        val channelDirectory = File(mirrorDir, channelPrefix).apply { mkdirs() }

        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val shell = if (isWindows) listOf("cmd.exe", "/c") else listOf("sh", "-c")

            // 1. Pull latest changes first before modifying files
            val pullCmd = "git pull --rebase origin main"
            val pullProcess = ProcessBuilder(shell + listOf(pullCmd))
                .directory(mirrorDir)
                .redirectErrorStream(true)
                .start()
            val pullOutput = pullProcess.inputStream.bufferedReader().readText()
            check(pullProcess.waitFor() == 0) { "Cannot synchronize update mirror: $pullOutput" }

            // 2. Copy APK and update mirror.json
            val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val apkFile = apkDir.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            var apkSha256 = ""
            var apkSizeBytes = 0L
            if (apkFile != null && apkFile.exists()) {
                ZipFile(apkFile).use { zip ->
                    val channelEntry = zip.getEntry("assets/release-channel.txt") ?: error("Rebuild this APK with channel metadata")
                    val codeEntry = zip.getEntry("assets/release-code.txt") ?: error("Rebuild this APK with version metadata")
                    require(zip.getInputStream(channelEntry).bufferedReader().use { it.readText().trim() } == publishingChannel) { "APK channel differs from -PupdateChannel" }
                    require(zip.getInputStream(codeEntry).bufferedReader().use { it.readText().trim() } == verCode.toString()) { "APK version differs from version.properties" }
                }
                val destApk = File(channelDirectory, "latest.apk")
                apkFile.copyTo(destApk, overwrite = true)
                apkSha256 = sha256Of(destApk)
                apkSizeBytes = destApk.length()
                println("UpdateMirrors: Copied release APK (${apkFile.name}) to ${destApk.absolutePath} (${destApk.length()} bytes, sha256 $apkSha256)")
            } else {
                error("Build a signed release APK before publishing: $apkDir")
            }

            // Mirror format v2.
            //
            // Every v1 field keeps its exact name, type and meaning, and the new
            // ones are purely additive, so a client that only knows v1 (any build
            // shipped before this one) still parses the file and still sees an
            // update. Gson ignores fields a client has no property for, and the
            // added properties all have defaults, so both directions are safe.
            val rawDownloadUrl = "https://raw.githubusercontent.com/lunadevph/update-mirror/main/${channelPrefix}latest.apk?v=$verCode"
            val mirrorFile = File(channelDirectory, "mirror.json")
            val jsonContent = """
{
  "schemaVersion": 2,
  "channel": "$publishingChannel",
  "buildNumber": "$displayBuildNumber",
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
            val commitPushCmd = "git add ${channelPrefix}mirror.json ${channelPrefix}latest.apk && (git diff --cached --quiet || git commit -m \"Update $publishingChannel mirror to v$verName (build $displayBuildNumber, code $verCode)\") && git push $pushRef main"
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
                error("Update mirror push failed; local commits are retained for retry")
            }
        } catch (e: Exception) {
            throw GradleException("UpdateMirrors failed; inspect the mirror checkout before retrying", e)
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
        println("App Version: $currentVersionName (Code: $currentVersionCode)")
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

        val createBody = """{"tag_name":"$tagName","name":"$tagName (Build $displayBuildNumber)","body":"$bodyJson","draft":false,"prerelease":false}"""
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
tasks.register("checkChangelog") {
    description = "Fails the build if app/changelog.txt is empty or has no bullets"
    group = "verification"
    doLast {
        val f = file("changelog.txt")
        if (!f.exists()) throw GradleException("app/changelog.txt is missing — update the release notes first.")
        val text = f.readText().trim()
        if (text.isEmpty()) throw GradleException("app/changelog.txt is empty — update the release notes first.")
        if (!text.lines().any { it.trim().startsWith("- ") }) {
            throw GradleException("app/changelog.txt has no '- ' bullets — update the release notes first.")
        }
        val bulletCount = text.lines().count { it.trim().startsWith("- ") }
        println("checkChangelog: OK ($bulletCount bullets)")
    }
}

tasks.register("uploadRelease") {
    description = "Builds release APK, updates mirror, and creates a GitHub Release"
    group = "publishing"
    dependsOn("checkChangelog", "assembleRelease")
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
