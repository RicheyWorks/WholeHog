rootProject.name = "wholehog"

// Composite build: WholeHog is engine twelve — the integration organism, and the only build
// that includes EVERY sibling (all thirteen others, Rub and Sizzle included). Gradle
// deduplicates the shared transitive includes (each engine includes ../SmokeHouse, which
// includes ../SuperBeefSort → ../CSRBT; Sizzle includes ../Twine, which re-includes SmokeHouse).
includeBuild("../SmokeHouse")
includeBuild("../Carver")
includeBuild("../Renderer")
includeBuild("../Brine")
includeBuild("../PitBoss")
includeBuild("../DryAge")
includeBuild("../Twine")
includeBuild("../SmokeSignal")
includeBuild("../Jerky")
includeBuild("../Rub")
includeBuild("../Sizzle")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
