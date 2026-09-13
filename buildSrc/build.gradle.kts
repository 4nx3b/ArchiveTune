plugins {
    kotlin("jvm") version "2.4.0"
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("org.apache.xmlgraphics:batik-all:1.19")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
}
