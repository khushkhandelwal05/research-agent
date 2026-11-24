plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // Optional provider: only used when provider=anthropic.
    implementation("com.anthropic:anthropic-java:3.3.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Compile for Java 21 (LTS) so reviewers can run it on any JDK >= 21.
    options.release = 21
    options.encoding = "UTF-8"
}

application {
    mainClass = "com.researchagent.Main"
}

tasks.named<JavaExec>("run") {
    // Relative paths (links file, runs/ output) resolve from the project root.
    workingDir = rootDir
}
