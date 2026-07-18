rootProject.name = "wholehog"

// Composite build: WholeHog is engine twelve — the integration organism, and the only build
// that includes EVERY sibling. Gradle deduplicates the shared transitive includes
// (each engine includes ../SmokeHouse, which includes ../SuperBeefSort → ../CSRBT).
includeBuild("../SmokeHouse")
includeBuild("../Carver")
includeBuild("../Renderer")
includeBuild("../Brine")
includeBuild("../PitBoss")
includeBuild("../DryAge")
includeBuild("../Twine")
includeBuild("../SmokeSignal")
includeBuild("../Jerky")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
