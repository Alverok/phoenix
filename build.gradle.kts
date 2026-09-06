plugins {
    application
    java
}

group = "com.phoenix"
version = "0.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.inject:guice:7.0.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.phoenix.App")
}

// NEW: forward the terminal's stdin to the running app, so App.java's
// interactive Scanner (list/dispatch/quit commands) can actually read
// keyboard input. Without this, Gradle leaves stdin disconnected and
// Scanner.nextLine() throws NoSuchElementException immediately.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}