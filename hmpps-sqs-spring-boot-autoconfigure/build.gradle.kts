import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "1.7.22"
  kotlin("plugin.spring") version "1.7.22"
  id("maven-publish")
  id("signing")
  id("com.adarshr.test-logger") version "3.2.0"
  id("com.github.ben-manes.versions") version "0.44.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.18"
  id("io.spring.dependency-management") version "1.1.0"
  id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
  id("org.owasp.dependencycheck") version "7.3.2"
  id("org.springframework.boot") version "2.7.6"
}

// Pinned to counter various CVEs with previous versions. Please remove this once Spring pulls in at least this version: https://docs.spring.io/spring-boot/docs/current/reference/html/dependency-versions.html
ext["snakeyaml.version"] = "1.33"

dependencies {
  implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:3.0.0-M3"))
  implementation(platform("software.amazon.awssdk:bom:2.18.28"))

  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  api("io.awspring.cloud:spring-cloud-aws-starter") { exclude("io.awspring.cloud", "spring-cloud-aws-autoconfigure") }
  implementation("io.awspring.cloud:spring-cloud-aws-sns")
  implementation("io.awspring.cloud:spring-cloud-aws-sqs")
  implementation("com.google.code.gson:gson:2.10")
  implementation("com.microsoft.azure:applicationinsights-core:3.4.4")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

  testImplementation("org.assertj:assertj-core:3.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
  testImplementation("org.mockito:mockito-junit-jupiter:4.9.0")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
  testImplementation("org.mockito:mockito-inline:4.9.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
  testImplementation("org.jetbrains.kotlin:kotlin-reflect")
}

publishing {
  repositories {
    mavenLocal()
  }
  publications {
    create<MavenPublication>("autoconfigure") {
      from(components["java"])
      pom {
        name.set(base.archivesName)
        artifactId = base.archivesName.get()
        description.set("A Spring Boot Autoconfigure library providing utilities for using amazon-sqs-java-messaging-lib")
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
  sign(publishing.publications["autoconfigure"])
}
java.sourceCompatibility = JavaVersion.VERSION_11

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

tasks {
  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = "11"
    }
  }

  withType<Test> {
    useJUnitPlatform()
  }

  withType<DependencyUpdatesTask> {
    rejectVersionIf {
      isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
  }
}

project.getTasksByName("check", false).forEach {
  val prefix = if (it.path.contains(":")) {
    it.path.substringBeforeLast(":")
  } else {
    ""
  }
  it.dependsOn("$prefix:ktlintCheck")
}
