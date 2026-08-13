// The Fabric module. Building it requires maven.fabricmc.net, libraries.minecraft.net and
// piston-meta.mojang.com. Environments without access to those hosts should build with
// -Pretina.coreOnly=true, which excludes this module; see docs/BUILDING.md.

plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
    `maven-publish`
}

val minecraftVersion = providers.gradleProperty("minecraft.version").get()
val loaderVersion = providers.gradleProperty("loader.version").get()
val fabricApiVersion = providers.gradleProperty("fabric.api.version").get()
val sodiumVersion = providers.gradleProperty("sodium.version").get()

base {
    archivesName = "retina"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java.version").get().toInt())
    }
    withSourcesJar()
}

loom {
    accessWidenerPath = file("src/main/resources/retina.accesswidener")
}

repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Minecraft 26.2 mods are compiled and shipped against Mojang's official names; there is
    // no intermediary remap step in this toolchain, which is why Sodium's class files
    // reference net.minecraft.client.renderer.* directly.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Sodium is a hard dependency: Retina drives its terrain pipeline rather than replacing
    // it. It is not embedded, and its Polyform Shield licence forbids redistribution, so it
    // is compile-scoped only.
    modCompileOnly("maven.modrinth:sodium:$sodiumVersion")
    modRuntimeOnly("maven.modrinth:sodium:$sodiumVersion")

    // The backend-neutral runtime, shaded into the mod jar.
    implementation(project(":retina-core"))
    include(project(":retina-core"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = providers.gradleProperty("java.version").get().toInt()
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version,
        "minecraft" to minecraftVersion,
        "loader" to loaderVersion,
        "sodium" to sodiumVersion,
    )
    inputs.properties(properties)
    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

tasks.jar {
    from(rootProject.file("LICENSE")) { rename { "LICENSE_retina" } }
    from(rootProject.file("NOTICE")) { rename { "NOTICE_retina" } }
}

// Reproducible archives: without these, the jar embeds file timestamps and filesystem
// ordering, and two builds of identical source produce different hashes.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions { unix("755") }
    filePermissions { unix("644") }
}
