import com.yandex.yatagan.gradle.ClasspathSourceGeneratorTask

plugins {
    id("yatagan.base-module")
}

val versionsToCheckLoaderCompatibility = listOf(
    "1.0.0",
    "1.1.0",
    "1.2.0",
)

val baseTestRuntime: Configuration by configurations.creating
val dynamicTestRuntime: Configuration by configurations.creating {
    extendsFrom(baseTestRuntime)
}
val compiledTestRuntime: Configuration by configurations.creating {
    extendsFrom(baseTestRuntime)
}

val intellijTestDriverRuntime: Configuration by configurations.creating
val intellijTestDriverCommandLine: Configuration by configurations.creating

versionsToCheckLoaderCompatibility.forEach { version ->
    configurations.register("kaptForCompatCheck$version")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":testing:source-set"))

    // Third-party test dependencies
    implementation(testingLibs.junit4)
    implementation(testingLibs.roomCompileTesting)

    // Base test dependencies
    implementation(project(":processor:common"))
    implementation(project(":core:model:impl"))
    implementation(project(":core:graph:impl"))
    implementation(project(":api:public"))
    implementation(project(":base"))

    // KSP dependencies
    implementation(project(":lang:ksp"))
    implementation(project(":processor:ksp"))

    // JAP dependencies
    implementation(project(":lang:jap"))
    implementation(project(":processor:jap"))

    // RT dependencies
    implementation(project(":lang:rt"))
    implementation(libs.poets.java)

    // IntelliJ dependencies
    implementation(project(path = ":intellij-plugin", configuration = "testDriverRmiApi"))

    // Heavy test dependencies
    testImplementation(project(":testing:procedural"))

    // For strings
    testImplementation(project(":validation:format"))

    baseTestRuntime(testingLibs.mockito.kotlin.get())  // required for heavy tests
    dynamicTestRuntime(project(":api:dynamic"))
    compiledTestRuntime(project(":api:public"))

    intellijTestDriverRuntime(project(path = ":intellij-plugin", configuration = "testDriver"))
    intellijTestDriverCommandLine(project(path = ":intellij-plugin", configuration = "testDriverCommandLine"))

    versionsToCheckLoaderCompatibility.forEach { version ->
        add("kaptForCompatCheck$version", "com.yandex.yatagan:processor-jap:$version")
    }
}

val genKaptClasspathForCompatCheckTasks = versionsToCheckLoaderCompatibility.map { version ->
    val versionId = version.replace("[.-]".toRegex(), "_")
    tasks.register<ClasspathSourceGeneratorTask>("generateKaptClasspathForCompatCheck_$versionId") {
        propertyName.set("KaptClasspathForCompatCheck$versionId")
        classpath.set(configurations.named("kaptForCompatCheck$version"))
    }
}

val generateDynamicApiClasspath by tasks.registering(ClasspathSourceGeneratorTask::class) {
    propertyName.set("DynamicApiClasspath")
    classpath.set(dynamicTestRuntime)
}

val generateCompiledApiClasspath by tasks.registering(ClasspathSourceGeneratorTask::class) {
    propertyName.set("CompiledApiClasspath")
    classpath.set(compiledTestRuntime)
}

val generateIntellijTestDriverRuntimeClasspath by tasks.registering(ClasspathSourceGeneratorTask::class) {
    propertyName.set("IntelliJTestDriverClasspath")
    classpath.set(intellijTestDriverRuntime)
}

tasks.withType<ClasspathSourceGeneratorTask>().configureEach {
    packageName.set("com.yandex.yatagan.generated")
}

tasks.named("compileKotlin") {
    dependsOn(
        generateDynamicApiClasspath,
        generateCompiledApiClasspath,
        generateIntellijTestDriverRuntimeClasspath,
        genKaptClasspathForCompatCheckTasks,
    )
}

val updateGoldenFiles by tasks.registering(Test::class) {
    group = "tools"
    description = "Launch tests in a special 'regenerate-golden' mode, where they are do not fail, " +
            "but write their *actual* results as *expected*. Use with care after you've changed some error-reporting " +
            "format and need to regenerate the actual results in batch"
    // Pass the resource directory absolute path
    systemProperty("com.yandex.yatagan.updateGoldenFiles",
        sourceSets.test.get().resources.sourceDirectories.singleFile.absolutePath)
}

tasks.test {
    // Needed for "heavy" tests, as they compile a very large Kotlin project in-process.
    jvmArgs = listOf("-Xmx4G", "-Xms256m")
    shouldRunAfter(updateGoldenFiles)

    // Increasing this will likely get a negative effect on tests performance as kotlin-compilation seems to be shared
    // between compilation invocations and I still haven't found a way to make it in-process.
    maxParallelForks = 1

    val intellijTestDriverCommandLine: FileCollection = intellijTestDriverCommandLine
    inputs.files(intellijTestDriverCommandLine)

    val jdk8 = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(8)) }

    doFirst {
        systemProperty("com.yandex.yatagan.intellij-test-driver-command-line",
            intellijTestDriverCommandLine.singleFile.absolutePath)
        systemProperty("com.yandex.yatagan.jdk8", jdk8.get().metadata.installationPath)
    }
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generateDynamicApiClasspath.map { it.generatedSourceDir })
            kotlin.srcDir(generateCompiledApiClasspath.map { it.generatedSourceDir })
            kotlin.srcDir(generateIntellijTestDriverRuntimeClasspath.map { it.generatedSourceDir })
            genKaptClasspathForCompatCheckTasks.forEach { taskProvider ->
                kotlin.srcDir(taskProvider.map { it.generatedSourceDir })
            }
        }
    }
}
