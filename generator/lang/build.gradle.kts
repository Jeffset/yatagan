plugins {
    id("daggerlite.artifact")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core-lang"))
    implementation(kotlin("stdlib"))
}