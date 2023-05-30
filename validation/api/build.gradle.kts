plugins {
    id("yatagan.artifact")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":lang:api"))
}