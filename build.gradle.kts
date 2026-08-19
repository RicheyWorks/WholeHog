plugins {
    `java-library`
    application
    `maven-publish`
    signing
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
    api("io.github.richeyworks:rub:0.1.0")
    api("io.github.richeyworks:sizzle:0.1.0")

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

// Phase 9 release prep: Central requires a javadoc jar per artifact.
java {
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// Phase 9 release prep: PGP signing + a local staging layout for the Central Portal bundle.
// Signing activates ONLY when SIGNING_KEY is present in the environment, so everyday local
// builds stay signature-free. Stage with: ./gradlew publishMavenPublicationToStagingRepository
publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, pass)
        sign(publishing.publications["maven"])
    }
}
