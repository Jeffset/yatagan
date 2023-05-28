import org.jetbrains.intellij.dependency.IdeaDependency
import org.jetbrains.intellij.dependency.PluginDependency

plugins {
    id("yatagan.base-module")
    id("org.jetbrains.intellij") version "1.13.3"
    `java-test-fixtures`
}

val yataganVersion: String by extra

version = yataganVersion
group = "com.yandex.yatagan"

val pluginsList = listOf(
    "com.intellij.java",
    "org.jetbrains.kotlin",
)

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

intellij {
    version.set("2023.1.2")
    type.set("IC")

    plugins.set(pluginsList)
}

dependencies {
    implementation(project(":base"))
    implementation(project(":lang:compiled"))
    implementation(project(":core:model:impl"))
    implementation(project(":core:graph:impl"))
    implementation(project(":validation:impl"))
    implementation(project(":validation:format"))

    testFixturesImplementation(project(":base"))
    testFixturesImplementation(project(":lang:api"))
    testFixturesImplementation(project(":lang:compiled"))
    testFixturesImplementation(project(":core:model:impl"))
    testFixturesImplementation(project(":core:graph:impl"))
    testFixturesImplementation(project(":validation:impl"))
    testFixturesImplementation(project(":validation:format"))
    testFixturesImplementation(project(":processor:common"))

    testFixturesRuntimeOnly(files(tasks.setupDependencies.flatMap { it.idea.map(IdeaDependency::jarFiles) }))
    testFixturesRuntimeOnly(files(intellij.pluginDependencies.map { it.map(PluginDependency::jarFiles) }))
}

afterEvaluate {
    // TODO: Generate these into a file and consume it in IntelliJTestLauncher.
    println(tasks.test.get().systemProperties)
    println(tasks.test.get().jvmArgs)
}
