plugins {
  kotlin("jvm") version "1.5.10"
  id("maven-publish")
  id("signing")
}

dependencies {
  api(project(":hmpps-sqs-spring-boot-autoconfigure"))
  api(platform("com.amazonaws:aws-java-sdk-bom:1.11.942"))
  api("com.amazonaws:amazon-sqs-java-messaging-lib:1.0.8")
  api("com.amazonaws:aws-java-sdk-sns:1.11.942")
  api("org.springframework.boot:spring-boot-starter-web")
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-actuator")
  api("com.microsoft.azure:applicationinsights-core:2.6.3")
  api("org.springframework:spring-jms")
}

// TODO move all the below into the parent gradle script? (and the same for the autoconfigure library?)
publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("starter") {
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
  sign(publishing.publications["starter"])
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
