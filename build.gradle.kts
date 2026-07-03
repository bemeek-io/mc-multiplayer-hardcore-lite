plugins {
    java
}

group = "com.evensteven"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Match this to your server's EXACT version. The scheme is <mcversion>.build.<n>.
    // 26.2.1 -> "26.2.1.build.+"  (the trailing .+ grabs the latest build of that version)
    // If Gradle says the version can't be found, check repo.papermc.io and adjust.
    compileOnly("io.papermc.paper:paper-api:26.2.1.build.+")
}

java {
    // Paper 26.x requires Java 25 to develop against.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
}
