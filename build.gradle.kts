// Flat test harness: all files sit in the repository root so they can be uploaded from a phone.
plugins {
    // Placeholder version, not verified as the newest. If the run says the plugin or version
    // is a problem, tell Joe/Claude the error text.
    kotlin("jvm") version "2.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

sourceSets {
    main { kotlin { srcDir("."); include("SyncCore.kt") } }
    test { kotlin { srcDir("."); include("SyncCoreTest.kt") } }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
