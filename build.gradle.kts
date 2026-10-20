import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    // Real interface default methods: no DefaultImpls bridges that override deprecated platform defaults.
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Built against the unified IntelliJ IDEA; the plugin only depends on com.intellij.modules.platform,
        // so it runs in PhpStorm as well (runIde can be pointed at a PhpStorm install, see README).
        intellijIdea("2025.3.6.1")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
        // The newest section of CHANGELOG.md, its "- " items as a list.
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { md ->
            val section = md.substringAfter("\n## ").substringAfter('\n').substringBefore("\n## ")
            val items = section.trim().split(Regex("""\n- """)).map { it.removePrefix("- ").replace(Regex("""\s*\n\s*"""), " ") }
            items.joinToString("", "<ul>", "</ul>") { "<li>" + it.replace("&", "&amp;").replace("<", "&lt;") + "</li>" }
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        // The IDE this plugin is for: the oldest supported PhpStorm and the current one.
        ides {
            create(IntelliJPlatformType.PhpStorm, "2025.3.6.1")
            create(IntelliJPlatformType.PhpStorm, "2026.2.3")
        }
    }
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
