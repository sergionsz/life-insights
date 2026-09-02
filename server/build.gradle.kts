plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.sergio.lifeinsights.server.MainKt")
}

dependencies {
    implementation(project(":sync-model"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)

    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":sync-model")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.embedded.postgres)
    // Apple Silicon. The library only bundles amd64 binaries by default, and the tests are meant
    // to run on the machine this was developed on as readily as in CI.
    testRuntimeOnly(libs.embedded.postgres.darwin.arm64)
}
