plugins {
    java
}

group = "dev.ticktriage"
version = "0.1.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "enginehub"
        url = uri("https://maven.enginehub.org/repo/")
    }
    maven {
        name = "jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    // The Folia schedulers ship inside paper-api, so Folia support needs no
    // extra dependency here.
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")

    // Optional claim plugins. compileOnly: the adapters are only loaded when
    // the corresponding plugin is actually installed on the server.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.18")
    compileOnly("com.github.TechFortress:GriefPrevention:16.18.4")
}

java {
    // Paper 26.2 requires JDK 25. Gradle will download a matching toolchain if
    // your default JDK is older.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    // The tests contain non-ASCII characters; without this the platform default
    // encoding decides whether they compile, which differs on Windows.
    options.encoding = "UTF-8"
}

tasks.processResources {
    // Keep plugin.yml's version in step with the build so you never ship a jar
    // whose reported version is a lie.
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

sourceSets {
    test {
        java.srcDir("src/test/java")
        // The suites are plain main() classes, not JUnit, so the test source
        // set only needs the main classes on its path.
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

/**
 * Every core suite. These have no Bukkit dependency by design, so they run
 * without a server - see run-core-tests.sh for the Gradle-free equivalent.
 */
val coreSuites = listOf("CoreTests", "CensusTests", "RemedyTests",
        "ProtectionTests", "HistoryTests")

coreSuites.forEach { suite ->
    tasks.register<JavaExec>("run$suite") {
        group = "verification"
        description = "Run $suite (no server required)"
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("dev.ticktriage.core.$suite")
    }
}

tasks.register("coreTests") {
    group = "verification"
    description = "Run every core test suite (no server required)"
    dependsOn(coreSuites.map { "run$it" })
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Benchmark the per-entity sampling cost"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ticktriage.core.Bench")
}

tasks.register<JavaExec>("demo") {
    group = "verification"
    description = "Print what an owner sees for three scenarios"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ticktriage.core.Demo")
}

tasks.check {
    dependsOn("coreTests")
}
