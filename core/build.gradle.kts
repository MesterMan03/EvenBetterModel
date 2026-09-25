plugins {
    alias(libs.plugins.convention.publish)
}

dependencies {
    api(project(":bettermodel-api"))

    compileOnly(libs.bundles.minecraft)
    compileOnly("com.mojang:authlib:7.0.61")

    compileOnly(libs.bundles.core)
    compileOnly(libs.cloud.core)

    // DynamicUV's exported geometry and skin-color data are exercised without a Minecraft server.
    testImplementation(libs.gson)
    testImplementation(libs.fastutil)
}
