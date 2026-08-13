plugins {
    base
}

val retinaMinecraft: String = providers.gradleProperty("retina.minecraft").get()

allprojects {
    group = "dev.retina"
    version = "${providers.gradleProperty("retina.version").get()}+mc$retinaMinecraft"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
