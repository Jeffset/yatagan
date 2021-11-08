plugins {
    id("daggerlite.artifact")
    id("java-test-fixtures")
}

val kspVersion: String by extra

dependencies {
    implementation(project(":api"))
    implementation(project(":process"))
    implementation(project(":ksp-driver-lang"))

    implementation(kotlin("stdlib"))
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")

    testFixturesImplementation(testFixtures(project(":testing")))
    testFixturesImplementation(kotlin("test"))
}