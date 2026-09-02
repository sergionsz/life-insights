plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The in-memory row store is shared with the server's tests and the app's, so both drive
    // the real sync logic rather than each keeping its own imitation of it.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
