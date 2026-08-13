plugins {
    `java-library`
}

val lwjglVersion = providers.gradleProperty("lwjgl.version").get()
val jomlVersion = providers.gradleProperty("joml.version").get()
val fastutilVersion = providers.gradleProperty("fastutil.version").get()
val gsonVersion = providers.gradleProperty("gson.version").get()
val junitVersion = providers.gradleProperty("junit.version").get()

// Natives are only needed to *run* (tests + game). The host classifier is detected so
// that `gradle test` can genuinely invoke shaderc/SPIRV-Cross on the build machine.
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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(providers.gradleProperty("java.version").get().toInt())
    }
    withSourcesJar()
}

dependencies {
    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    // Vulkan bindings + shader toolchain. `lwjgl` core is `compileOnly`-ish at runtime in
    // the game (Minecraft ships it), but the core module is standalone-testable so it is a
    // normal api dependency here and excluded from the mod jar by the Fabric module.
    api("org.lwjgl:lwjgl")
    api("org.lwjgl:lwjgl-vulkan")
    api("org.lwjgl:lwjgl-shaderc")
    api("org.lwjgl:lwjgl-spvc")
    api("org.lwjgl:lwjgl-vma")
    api("org.joml:joml:$jomlVersion")
    api("it.unimi.dsi:fastutil:$fastutilVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")

    testRuntimeOnly("org.lwjgl:lwjgl::$nativeClassifier")
    testRuntimeOnly("org.lwjgl:lwjgl-shaderc::$nativeClassifier")
    testRuntimeOnly("org.lwjgl:lwjgl-spvc::$nativeClassifier")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = providers.gradleProperty("java.version").get().toInt()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Vulkan-device tests are opt-in; shader compilation tests always run.
    systemProperty("retina.test.vulkan", providers.gradleProperty("retina.test.vulkan").orNull ?: "false")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Retina Core",
            "Implementation-Version" to project.version,
        )
    }
}
