plugins {
    alias(libs.plugins.convention.standard)
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val minecraft = property("minecraft_version").toString()

val javadocJar = tasks.register<Jar>("javadocJar") {
    description = "Makes javadoc."
    dependsOn(tasks.dokkaGenerate)
    archiveClassifier = "javadoc"
    from(layout.buildDirectory.dir("dokka/html").orNull?.asFile)
}

runPaper {
    disablePluginJarDetection()
}

val bettermodel get() = project(":platform:bettermodel-paper").tasks.named<Jar>("shadowJar").flatMap {
    it.archiveFile
}
val bettermodelTest get() = project(":test-plugin").tasks.jar.flatMap {
    it.archiveFile
}

runPaper.folia.registerTask {
    pluginJars(bettermodel, bettermodelTest)
    minecraftVersion(minecraft)
}

tasks {
    runServer {
        pluginJars(fileTree("plugins"))
        pluginJars(bettermodel, bettermodelTest)
        minecraftVersion(minecraft)
        downloadPlugins {
            hangar("ViaVersion", libs.versions.viaversion.get())
            hangar("ViaBackwards", libs.versions.viaversion.get())
            hangar("Skript", libs.versions.skript.get())
        }
    }
    build {
        finalizedBy(
            javadocJar
        )
    }
    jar {
        enabled = false
    }
}
