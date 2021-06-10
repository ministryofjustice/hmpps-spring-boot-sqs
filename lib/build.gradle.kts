import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.5.10"
  kotlin("plugin.spring") version "1.5.10"
  id("maven-publish")
  id("signing")
  id("com.adarshr.test-logger") version "3.0.0"
  id("com.github.ben-manes.versions") version "0.39.0"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"
  id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
  id("org.owasp.dependencycheck") version "6.2.0"
  id("org.springframework.boot") version "2.5.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.17"
}

base.archivesBaseName = "hmpps-spring-boot-sqs"
version = "0.2.1"

dependencies {
  api("com.amazonaws:amazon-sqs-java-messaging-lib:1.0.8")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-security")

  testImplementation("org.assertj:assertj-core:3.19.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.8.0-M1")
  testImplementation("org.mockito:mockito-junit-jupiter:3.10.0")
  testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.mockito:mockito-inline:3.10.0")
}

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
signing {
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications["maven"])
}
java.sourceCompatibility = JavaVersion.VERSION_16

tasks.bootJar {
  enabled = false
}

tasks.jar {
  enabled = true
}

repositories {
  mavenLocal()
  mavenCentral()
}

java {
  withSourcesJar()
  withJavadocJar()
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
