plugins {
  kotlin("jvm") version "1.5.10"
  id("maven-publish")
  id("signing")
}

dependencies {
  // implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-autoconfigure:${project.version}")
  // implementation("uk.gov.justice.service.hmpps:hmpps-spring-boot-sqs:${project.version}") // TODO replace with above line when published
  api(project(":hmpps-sqs-spring-boot-autoconfigure")) // TODO replace with above line when publishing
}

// TODO move all the below into the parent gradle script? (and the same for the autoconfigure library?)
publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      pom {
        name.set(base.archivesBaseName)
        artifactId = base.archivesBaseName
        description.set("A Spring Boot Starter library providing utilities for using amazon-sqs-java-messaging-lib")
        url.set("https://github.com/ministryofjustice/hmpps-spring-boot-sqs")
        licenses {
          license {
            name.set("MIT")
            url.set("https://opensource.org/licenses/MIT")
          }
        }
        developers {
          developer {
            id.set("mikehalmamoj")
            name.set("Mike Halma")
            email.set("mike.halma@digital.justice.gov.uk")
          }
        }
        scm {
          url.set("https://github.com/ministryofjustice/hmpps-spring-boot-sqs")
        }
      }
    }
  }
}
signing {
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications["maven"])
}
java.sourceCompatibility = JavaVersion.VERSION_16

repositories {
  mavenLocal()
  mavenCentral()
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}
