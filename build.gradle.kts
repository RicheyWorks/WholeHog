plugins {
    `java-library`
    application
    `maven-publish`
}

group = "io.github.richeyworks"
version = "0.1.0"

java {
    withSourcesJar()
}

application {
    // The exhibit: one main() that runs the whole organism and prints every engine's vitals.
    mainClass.set("io.github.richeyworks.wholehog.Exhibit")
    applicationDefaultJvmArgs = listOf(
        "-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Every engine, resolved to live sibling sources via the composite chain.
    api("io.github.richeyworks:smokehouse:0.1.0")
    api("io.github.richeyworks:carver:0.1.0")
    api("io.github.richeyworks:renderer:0.1.0")
    api("io.github.richeyworks:brine:0.1.0")
    api("io.github.richeyworks:pitboss:0.1.0")
    api("io.github.richeyworks:dryage:0.1.0")
    api("io.github.richeyworks:twine:0.1.0")
    api("io.github.richeyworks:smokesignal:0.1.0")
    api("io.github.richeyworks:jerky:0.1.0")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("log4j2.loggerContextFactory",
            "org.apache.logging.log4j.simple.SimpleLoggerContextFactory")
    systemProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "wholehog"
            from(components["java"])
            pom {
                name = "WholeHog"
                description = "The integration organism: all eleven CSRBT-ecosystem engines composed, seeded, and asserted together."
                url = "https://github.com/RicheyWorks/WholeHog"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "RicheyWorks"
                        name = "Richmond"
                    }
                }
                scm {
                    url = "https://github.com/RicheyWorks/WholeHog"
                    connection = "scm:git:https://github.com/RicheyWorks/WholeHog.git"
                }
            }
        }
    }
}
