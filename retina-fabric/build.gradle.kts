// The Fabric module. Building it requires maven.fabricmc.net, libraries.minecraft.net and
// piston-meta.mojang.com. Environments without access to those hosts should build with
// -Pretina.coreOnly=true, which excludes this module; see docs/BUILDING.md.

plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    `maven-publish`
}

val minecraftVersion = providers.gradleProperty("minecraft.version").get()
val loaderVersion = providers.gradleProperty("loader.version").get()
val fabricApiVersion = providers.gradleProperty("fabric.api.version").get()
val sodiumVersion = providers.gradleProperty("sodium.version").get()
val junitVersion = providers.gradleProperty("junit.version").get()
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch.startsWith("aarch64") || arch.startsWith("arm")
    when {
        os.contains("win") -> if (arm) "natives-windows-arm64" else "natives-windows"
        os.contains("mac") || os.contains("darwin") -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

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
    // Minecraft 26.2 ships unobfuscated. The non-remapping Loom plugin compiles directly
    // against those names; declaring a mappings layer is both unnecessary and invalid.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Sodium is a hard dependency: Retina drives its terrain pipeline rather than replacing
    // it. It is not embedded, and its Polyform Shield licence forbids redistribution, so it
    // is compile-scoped only.
    compileOnly("maven.modrinth:sodium:$sodiumVersion")
    runtimeOnly("maven.modrinth:sodium:$sodiumVersion")

    // The backend-neutral runtime, shaded into the mod jar.
    implementation(project(":retina-core"))
    include(project(":retina-core"))

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.lwjgl:lwjgl::$nativeClassifier")
    testRuntimeOnly("org.lwjgl:lwjgl-shaderc::$nativeClassifier")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = providers.gradleProperty("java.version").get().toInt()
}

tasks.test {
    useJUnitPlatform()
}

// Source acceptance packs are versioned at the repository root; the development client reads
// from its ignored run directory. Keep copying explicit so normal client runs never overwrite a
// locally edited probe.
tasks.register<Copy>("syncAcceptancePacks") {
    from(rootProject.layout.projectDirectory.dir("acceptance-packs"))
    into(layout.projectDirectory.dir("run/shaderpacks"))
}

// The development client consumes the copied packs directly. Declare that relationship so a
// one-command live run is deterministic under Gradle's task-output validation.
tasks.named("runClient") {
    dependsOn("syncAcceptancePacks")
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
