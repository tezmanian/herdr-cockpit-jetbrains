plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Built against the unified IntelliJ IDEA; the plugin only depends on com.intellij.modules.platform,
        // so it runs in PhpStorm as well (runIde can be pointed at a PhpStorm install, see README).
        intellijIdea("2025.3.6.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
    buildSearchableOptions = false
}

tasks.runIde {
    // Point the sandbox IDE at a throwaway Herdr session: -PherdrSocket=~/.config/herdr/sessions/<name>/herdr.sock
    providers.gradleProperty("herdrSocket").orNull?.let { sock ->
        environment("HERDR_SOCKET_PATH", sock)
        Regex("""/sessions/([^/]+)/herdr\.sock$""").find(sock)?.let { environment("HERDR_SESSION", it.groupValues[1]) }
    }
    providers.gradleProperty("openProject").orNull?.let { args(it) }
    jvmArgs("-Didea.trust.all.projects=true", "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->")
}
