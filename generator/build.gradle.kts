plugins {
    id("daggerlite.artifact")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":base"))
    implementation(project(":generator-poetry"))
    implementation(project(":generator-lang"))
    implementation(kotlin("stdlib"))
}