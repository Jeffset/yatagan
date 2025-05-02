plugins {
    id("yatagan.implementation-artifact")
}

dependencies {
    api(project(":base:impl"))
    api(project(":core:model:api"))
    api(project(":core:graph:api"))
    api(project(":validation:api"))

    testImplementation(kotlin("test"))
}