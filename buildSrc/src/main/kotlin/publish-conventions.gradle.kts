import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import kotlin.io.encoding.Base64

plugins {
    id("standard-conventions")
    id("com.vanniktech.maven.publish")
    signing
}

rootProject.dependencies.dokka(project)

val artifactBaseId = name
val artifactVersion = project.version.toString().run {
    BUILD_NUMBER?.let { substringBeforeLast("-$it") } ?: this
}

signing {
    val key = System.getenv("SIGNING_KEY")?.let {
        Base64.decode(it.toByteArray()).toString(Charsets.UTF_8)
    }
    val password = System.getenv("SIGNING_PASSWORD")
    if (!key.isNullOrEmpty() && !password.isNullOrEmpty()) {
        useInMemoryPgpKeys(
            key,
            password
        )
    } else useGpgCmd()
}

dependencies {
    api(libs.bundles.library)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("nf.signPublications").map(String::toBoolean).orElse(false).get()) {
        signAllPublications()
    }
    coordinates("io.github.toxicity188", artifactBaseId, artifactVersion)
    configure(JavaLibrary(
        javadocJar = JavadocJar.Javadoc(),
        sourcesJar = SourcesJar.Sources(),
    ))
    pom {
        name = artifactBaseId
        description = "Modern Bedrock model engine for Minecraft Java Edition"
        inceptionYear = "2024"
        url = "https://github.com/NightFantasyStudios/BetterModel/"
        licenses {
            license {
                name = "MIT License"
                url = "https://mit-license.org/"
            }
        }
        developers {
            developer {
                id = "toxicity188"
                name = "toxicity188"
                url = "https://github.com/NightFantasyStudios/"
            }
        }
        scm {
            url = "https://github.com/NightFantasyStudios/BetterModel/"
            connection = "scm:git:git://github.com/NightFantasyStudios/BetterModel.git"
            developerConnection = "scm:git:ssh://git@github.com/NightFantasyStudios/BetterModel.git"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            val githubRepository = System.getenv("GITHUB_REPOSITORY") ?: "NightFantasyStudios/${rootProject.name}"
            url = uri("https://maven.pkg.github.com/$githubRepository")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "NightFantasyStudios"
                password = System.getenv("PACKAGES_API_TOKEN") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
