import java.util.jar.JarFile
import java.time.ZonedDateTime
import java.time.ZoneOffset

plugins {
    id("java")
}

val patchline: String by project
val hytaleHome: String = "${System.getProperty("user.home")}/AppData/Roaming/Hytale"
val hytaleServerJar: String = (project.findProperty("hytaleServerJar") as String?)
    ?: "$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar"

val serverVersion: String by lazy {
    val jarFile = file(hytaleServerJar)
    if (jarFile.exists()) {
        JarFile(jarFile).use { jar ->
            jar.manifest.mainAttributes.getValue("Implementation-Version")
                ?: error("Could not read Implementation-Version from HytaleServer.jar manifest")
        }
    } else {
        logger.warn("HytaleServer.jar not found at $hytaleServerJar — using 'unknown' for server version")
        "unknown"
    }
}

group = "com.msgames"
val buildDate = ZonedDateTime.now(ZoneOffset.UTC)
version = String.format("%d.%d.%d-%d", buildDate.year, buildDate.monthValue, buildDate.dayOfMonth, buildDate.toLocalTime().toSecondOfDay())

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files(hytaleServerJar))
}

// Update manifest.json with current version and server version before packaging
tasks.register("updatePluginManifest") {
    val manifestFile = file("src/main/resources/manifest.json")
    val pluginVersion = project.version.toString()

    doLast {
        val sv = serverVersion
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parseText(manifestFile.readText()) as MutableMap<String, Any>
        manifest["Version"] = pluginVersion
        manifest["ServerVersion"] = sv

        val json = groovy.json.JsonBuilder(manifest).toPrettyString()
        manifestFile.writeText(json + "\n")
        logger.lifecycle("Updated manifest.json: Version=$pluginVersion, ServerVersion=$sv")
    }
}

// Build the content pack ZIP from resources
val buildPackZip = tasks.register<Zip>("buildPackZip") {
    group = "build"
    description = "Packages the content pack as a ZIP file"
    dependsOn("updatePluginManifest")
    from("src/main/resources")
    archiveBaseName.set("BiggerStacksBackpacks")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("pack"))
}

tasks.named("build") {
    dependsOn(buildPackZip)
    mustRunAfter("clean")
}

tasks.register("release") {
    group = "build"
    dependsOn("clean", "build")
    description = "Builds the content pack and creates a GitHub release. Usage: ./gradlew release [-Ppatchline=beta]"

    val tag = "v${project.version}"
    val projectDir = project.projectDir
    val releasePatchline = patchline
    val releaseServerVersion = serverVersion

    doLast {
        for (cmd in listOf("git", "gh")) {
            try {
                ProcessBuilder(cmd, "--version").start().waitFor()
            } catch (_: Exception) {
                error("'$cmd' is not available on PATH. Install it and run this task from a terminal.")
            }
        }

        val zipFile = fileTree("build/pack") { include("*.zip") }.singleFile

        fun run(vararg args: String) {
            val process = ProcessBuilder(*args)
                .directory(projectDir)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) error("Command failed (exit $exitCode): ${args.joinToString(" ")}")
        }

        val commitProcess = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(projectDir)
            .start()
        val commitHash = commitProcess.inputStream.bufferedReader().readText().trim()
        commitProcess.waitFor()

        val releaseNotes = """
            ## Build Info
            - **Patchline:** $releasePatchline
            - **Server Version:** $releaseServerVersion
            - **Commit:** $commitHash
        """.trimIndent()

        run("git", "tag", tag)
        run("git", "push", "origin", tag)

        run("gh", "release", "create", tag,
            "\"${zipFile.absolutePath}\"",
            "--title", tag,
            "--notes", releaseNotes,
            "--generate-notes")

        logger.lifecycle("Release $tag created with ${zipFile.name}")
    }
}
