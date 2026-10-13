import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
    java
}

group = "com.phoenix"
version = "0.2.0"

java {
    // Machine currently has JDK 21 installed, so we build against that for now.
    // The Design & Solution doc chose Java 17 specifically so JIVE can instrument
    // the Core later (Sec. 8, Technology Stack). Before locking the final baseline,
    // test JIVE against 17 AND 21 and pick based on that result, not on habit.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // MapBinder used in PhoenixModule ships inside the core guice jar (no separate
    // guice-multibindings artifact needed since Guice 4.0+).
    implementation("com.google.inject:guice:7.0.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.googlecode.lanterna:lanterna:3.1.5")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.phoenix.App")

    // Day 2 note: the old "-Dphoenix.adapter=..." JVM arg is gone.
    // Adapter selection was a Day-1-only concept for proving IoC with one
    // active adapter; Phoenix Core now registers Linux + Android + ESP32
    // nodes simultaneously and picks per-task via TaskDispatcher/Scheduler,
    // so there's no longer a single "active adapter" to select at launch.
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`

    System.getProperty("phoenix.advertiseAddress")?.let {
        systemProperty(
            "phoenix.advertiseAddress",
            it
        )
    }
}

tasks.test {
    useJUnitPlatform()

    // A hung test (a socket that never answers, a latch that never opens) must
    // fail the build instead of holding a CI runner until GitHub kills it.
    timeout.set(Duration.ofMinutes(10))

    // Print what failed, and why, straight into the build log. Without this a
    // failing CI run only says "N tests completed, 1 failed".
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
