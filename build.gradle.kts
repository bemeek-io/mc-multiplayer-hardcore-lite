plugins {
    java
}

group = "com.evensteven"
// CI passes the real release version with -PpluginVersion=x.y.z
version = providers.gradleProperty("pluginVersion").getOrElse("2.0.0-dev")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Tracks the latest build of the 26.2 line; adjust the prefix when the
    // server jumps to a new Minecraft version (check repo.papermc.io).
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    // Paper 26.x requires Java 25 to develop against.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("MultiplayerHardcoreLite")
}
