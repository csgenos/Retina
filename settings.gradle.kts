pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Only required for the `retina-fabric` module.
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
    }
}

rootProject.name = "retina"

include("retina-core")

// The Fabric module needs Fabric Loom, Fabric Loader, Minecraft and Sodium, all of
// which are resolved from maven.fabricmc.net / libraries.minecraft.net. Environments
// without access to those hosts can still build and test the backend-neutral core with
//
//     ./gradlew build -Pretina.coreOnly=true
//
// See docs/BUILDING.md for the full matrix.
val coreOnly = (providers.gradleProperty("retina.coreOnly").orNull ?: "false").toBoolean()
if (!coreOnly) {
    include("retina-fabric")
}
