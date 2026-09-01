plugins {
    java
}

group = "me.sirborb"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// ponytail: no JUnit. model/ and api/ are pure JDK by design, so the self-check is a
// main() with -ea asserts. Add a framework if these ever need fixtures or parallelism.
sourceSets {
    create("selfcheck") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val selfcheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the parser and sanitizer assertions."
    mainClass = "me.sirborb.plugincloset.SelfCheck"
    classpath = sourceSets["selfcheck"].runtimeClasspath
    jvmArgs("-ea")
}

tasks.check { dependsOn(selfcheck) }

// Hits the real Modrinth and Hangar APIs. Run by hand, never part of `build`.
tasks.register<JavaExec>("livecheck") {
    group = "verification"
    description = "Queries both live APIs once and prints a merged page."
    mainClass = "me.sirborb.plugincloset.LiveCheck"
    classpath = sourceSets["selfcheck"].runtimeClasspath
    args = (project.findProperty("liveArgs") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}
