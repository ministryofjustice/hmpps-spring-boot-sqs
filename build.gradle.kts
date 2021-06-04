import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.5.10"
  kotlin("plugin.spring") version "1.5.10"
  id("maven-publish")
  id("signing")
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
  id("com.adarshr.test-logger") version "3.0.0"
  id("com.github.ben-manes.versions") version "0.39.0"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"
  id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
  id("org.owasp.dependencycheck") version "6.2.0"
  id("org.springframework.boot") version "2.5.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.17"
}

group = "uk.gov.justice.service.hmpps"
version = "0.1.1"

dependencies {
  implementation("org.springframework.boot:spring-boot-starter")
  implementation("com.amazonaws:amazon-sqs-java-messaging-lib:1.0.8")

  implementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.assertj:assertj-core:3.19.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.8.0-M1")
  testImplementation("org.mockito:mockito-junit-jupiter:3.10.0")
  testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
}

java.sourceCompatibility = JavaVersion.VERSION_16

tasks.bootJar {
  enabled = false
}

repositories {
  mavenLocal()
  mavenCentral()
}

java {
  withSourcesJar()
  withJavadocJar()
}

publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      pom {
        name.set("hmpps-spring-boot-sqs ")
        description.set("A helper library providing utilities for using amazon-sqs-java-messaging-lib")
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

nexusPublishing {
  repositories {
    create("sonatype") {
      nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
      snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
      username.set(System.getenv("OSSRH_USERNAME"))
      password.set(System.getenv("OSSRH_PASSWORD"))
    }
  }
}
signing {
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications["maven"])
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    jvmTarget = "16"
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
}

project.getTasksByName("check", false).forEach {
  val prefix = if (it.path.contains(":")) {
    it.path.substringBeforeLast(":")
  } else {
    ""
  }
  it.dependsOn("$prefix:ktlintCheck")
}
