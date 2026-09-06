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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`

    System.getProperty("phoenix.advertiseAddress")?.let {
        systemProperty("phoenix.advertiseAddress", it)
    }
}

tasks.test {
    useJUnitPlatform()
}