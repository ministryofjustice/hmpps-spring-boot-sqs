import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.5.10"
  kotlin("plugin.spring") version "1.5.10"
  id("maven-publish")
  id("com.adarshr.test-logger") version "3.0.0"
  id("com.github.ben-manes.versions") version "0.38.0"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"
  id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
  id("org.owasp.dependencycheck") version "6.1.6"
  id("org.springframework.boot") version "2.5.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.16"
}

group = "uk.gov.justice.hmpps"
version = "0.0.1-SNAPSHOT"

dependencies {
  implementation("com.amazonaws:amazon-sqs-java-messaging-lib:1.0.8")
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
}

publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("maven") {
      groupId = "uk.gov.justice.hmpps"
      artifactId = "hmpps-spring-boot-sqs"
      version = "0.0.1-SNAPSHOT"

      from(components["java"])
    }
  }
}

fun isNonStable(version: String): Boolean {
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    freeCompilerArgs = listOf("-Xjsr305=strict")
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
