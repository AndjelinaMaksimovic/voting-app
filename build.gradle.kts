plugins {
    kotlin("jvm") version "1.9.25"
    id("io.ktor.plugin") version "2.3.12"
    kotlin("plugin.serialization") version "1.9.25"
}

group = "com.voting"
version = "0.0.1"

application {
    mainClass.set("com.voting.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
    implementation("io.ktor:ktor-server-cors-jvm:2.3.12")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}
