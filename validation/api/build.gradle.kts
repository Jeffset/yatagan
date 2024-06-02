plugins {
    id("yatagan.stable-api-artifact")
}

dependencies {
    api(project(":lang:api"))
}

kotlin {
    explicitApi()
}