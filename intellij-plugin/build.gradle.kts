plugins {
    id("yatagan.base-module")
    id("org.jetbrains.intellij") version "1.13.3"
//    `java-test-fixtures`
}

val yataganVersion: String by extra

version = yataganVersion
group = "com.yandex.yatagan"

val pluginsList = listOf(
    "com.intellij.java",
    "org.jetbrains.kotlin",
)

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellij {
//    version.set("2023.1.2")
//    type.set("IC")
    localPath.set("/home/jeffset/.local/share/JetBrains/Toolbox/apps/IDEA-U/ch-0/232.6095.10")
    type.set("IU")

    plugins.set(pluginsList)
}

dependencies {
    implementation(project(":base"))
    implementation(project(":lang:compiled"))
    implementation(project(":core:model:impl"))
    implementation(project(":core:graph:impl"))
    implementation(project(":validation:impl"))
    implementation(project(":validation:format"))
}

// Setup Test Driver Artifact
val testDriverRmiApi by sourceSets.registering
val testDriver by sourceSets.registering

val testDriverRmiApiJar by tasks.registering(Jar::class) {
    archiveClassifier.set("test-driver-rmi-api")

    from(testDriverRmiApi.map { it.output })
}
val testDriverJar by tasks.registering(Jar::class) {
    archiveClassifier.set("test-driver")

    from(testDriver.map { it.output })
    dependsOn(tasks.jar)
    dependsOn(testDriverRmiApiJar)
}

@Suppress("USELESS_ELVIS")
val generateTestDriverCommandLine by tasks.registering {
    val outputFile = project.layout.buildDirectory.file("testDriverCommandLine.txt")
        .also { outputs.file(it) }

    val testSystemProperties = project.provider { tasks.test.get().systemProperties }
        .also { inputs.property("testSystemProperties", it) }
    val testJvmArgs = project.provider { tasks.test.get().jvmArgs ?: emptyList() }
        .also { inputs.property("testJvmArgs", it) }
    val testExecutable = project.provider { tasks.test.get().executable }
        .also { inputs.property("testExecutable", it) }

    doFirst {
        outputFile.get().asFile.writeText(buildString {
            appendLine(testExecutable.get())
            testJvmArgs.get().joinTo(this, separator = "\n")
            appendLine()
            testSystemProperties.get().entries.joinTo(this, separator = "\n") { (key, value) -> "-D$key=$value" }
            appendLine()
        })
    }
}

dependencies {
    "testDriverImplementation"(project(":processor:common"))
    "testDriverImplementation"(libs.kotlinx.coroutines)
    "testDriverImplementation"(files(tasks.jar))  // Depend on `main` source set.
    "testDriverImplementation"(files(testDriverRmiApiJar))  // Depend on `RMI API` source set.

    // Include full test classpath, as IntelliJ gradle plugin configures it for IDE to launch and work properly.
    "testDriverRuntimeOnly"(tasks.test.map { it.classpath })
}

configurations {
    named("testDriverCompileClasspath") {
        // test driver inherits all `main` dependencies to compile.
        extendsFrom(compileClasspath.get())
    }

    register("testDriver") {
        isCanBeConsumed = true
        isCanBeResolved = false
        // Equals full test driver source set runtime classpath.
        extendsFrom(findByName("testDriverRuntimeClasspath"))
    }

    register("testDriverRmiApi") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }

    register("testDriverCommandLine") {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
}

artifacts {
    add("testDriver", testDriverJar)
    add("testDriverRmiApi", testDriverRmiApiJar)
    add("testDriverCommandLine", generateTestDriverCommandLine)
}

tasks.runIde {
    maxHeapSize = "4G"
}