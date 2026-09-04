plugins {
    application
    java
}

group = "com.phoenix"
version = "0.1.0"

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

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.phoenix.App")

    applicationDefaultJvmArgs = listOf(
        "-Dphoenix.adapter=${System.getProperty("phoenix.adapter", "linux")}"
    )
}

tasks.test {
    useJUnitPlatform()
}
