import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  kotlin("jvm") version "2.0.10"
  id("maven-publish")
  id("signing")
  id("com.github.ben-manes.versions") version "0.51.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.18"
}

dependencies {
  api(platform("io.awspring.cloud:spring-cloud-aws-dependencies:3.1.1"))
  api(project(":hmpps-sqs-spring-boot-autoconfigure"))
  api(platform("software.amazon.awssdk:bom:2.27.6"))
  api("software.amazon.awssdk:sns")
  api("io.awspring.cloud:spring-cloud-aws-starter") { exclude("io.awspring.cloud", "spring-cloud-aws-autoconfigure")}
  api("io.awspring.cloud:spring-cloud-aws-sns")
  api("io.awspring.cloud:spring-cloud-aws-sqs")
  api("software.amazon.awssdk:sts")
  api(platform("org.springframework.boot:spring-boot-dependencies:3.3.2"))
  api("org.springframework.boot:spring-boot-starter-web")
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-actuator")
  api("com.microsoft.azure:applicationinsights-core:3.5.4")
}

publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("starter") {
      from(components["java"])
      pom {
        name.set(base.archivesName)
        artifactId = base.archivesName.get()
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

tasks.withType<PublishToMavenLocal> {
  signing {
    setRequired { false }
  }
}

signing {
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications["starter"])
}
java.sourceCompatibility = JavaVersion.VERSION_21

kotlin {
  jvmToolchain(21)
}

repositories {
  mavenLocal()
  mavenCentral()
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}

tasks.withType<DependencyUpdatesTask> {
  rejectVersionIf {
    isNonStable(candidate.version) && !isNonStable(currentVersion)
  }
}
