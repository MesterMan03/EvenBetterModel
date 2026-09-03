plugins {
    id("standard-conventions")
    id("io.papermc.paperweight.userdev")
}

if (project.name.startsWith("v1_21_")) {
    paperweight {
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
    }
}

dependencies {
    compileOnly(project(":bettermodel-api"))
    compileOnly(project(":bettermodel-api:bettermodel-bukkit-api"))
}
