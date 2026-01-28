import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
  kotlin("jvm") version "2.3.0"
  id("maven-publish")
  id("signing")
  id("com.github.ben-manes.versions") version "0.53.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.19"
}

dependencies {
  api(project(":hmpps-sqs-spring-boot-autoconfigure"))
  api(platform("software.amazon.awssdk:bom:2.41.16"))
  api("software.amazon.awssdk:sns")
  // couldn't use spring-cloud-aws-dependencies platform bom as it brings in spring-modulith-events-aws-sns at 1.4.0-SNAPSHOT
  // this then stopped the library being published with an error
  // - Dependency management dependencies to SNAPSHOT versions not allowed for dependency: org.springframework.modulith:spring-modulith-events-aws-sns
  api("io.awspring.cloud:spring-cloud-aws-starter:3.4.2") { exclude("io.awspring.cloud", "spring-cloud-aws-autoconfigure") }
  api("io.awspring.cloud:spring-cloud-aws-sns:3.4.2")
  api("io.awspring.cloud:spring-cloud-aws-sqs:3.4.2")
  api("software.amazon.awssdk:sts")
  api(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))
  api("org.springframework.boot:spring-boot-starter-web")
  api("org.springframework.boot:spring-boot-starter-security")
  api("org.springframework.boot:spring-boot-starter-actuator")
  api("com.microsoft.azure:applicationinsights-core:3.7.7")
  api("org.springframework.boot:spring-boot-jackson2")
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
