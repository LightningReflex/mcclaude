plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "com.mcclaude"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks {
    shadowJar {
        archiveFileName.set("McClaude.jar")
        relocate("okhttp3", "com.mcclaude.libs.okhttp3")
        relocate("okio", "com.mcclaude.libs.okio")
    }
    build {
        dependsOn(shadowJar)
    }
}
