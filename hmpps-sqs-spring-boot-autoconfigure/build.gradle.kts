import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.1.10"
  kotlin("plugin.spring") version "2.1.10"
  id("maven-publish")
  id("signing")
  id("com.adarshr.test-logger") version "4.0.0"
  id("com.github.ben-manes.versions") version "0.52.0"
  id("se.patrikerdes.use-latest-versions") version "0.2.18"
  id("io.spring.dependency-management") version "1.1.7"
  id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
  id("org.owasp.dependencycheck") version "8.4.3"
  id("org.springframework.boot") version "3.4.2"
}

dependencyManagement {
  imports {
    mavenBom("software.amazon.awssdk:bom:2.25.34")
    mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:3.1.1")
  }
}

ext["netty.version"] = "4.1.118.Final"

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  api("io.awspring.cloud:spring-cloud-aws-starter") { exclude("io.awspring.cloud", "spring-cloud-aws-autoconfigure") }
  implementation("io.awspring.cloud:spring-cloud-aws-sns")
  implementation("io.awspring.cloud:spring-cloud-aws-sqs")
  implementation("com.google.code.gson:gson:2.12.1")
  implementation("com.microsoft.azure:applicationinsights-core:3.7.0")
  implementation("io.opentelemetry:opentelemetry-api")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

  testImplementation("org.assertj:assertj-core:3.27.3")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
  testImplementation("org.mockito:mockito-inline:5.2.0")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
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

tasks.withType<PublishToMavenLocal> {
  signing {
    setRequired { false }
  }
}

signing {
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications["autoconfigure"])
}
java.sourceCompatibility = JavaVersion.VERSION_21

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
  val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
  val regex = "^[0-9,.v-]+(-r)?$".toRegex()
  val isStable = stableKeyword || regex.matches(version)
  return isStable.not()
}

kotlin {
  jvmToolchain(21)
}

tasks {
  withType<KotlinCompile> {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
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

dependencyCheck {
  failBuildOnCVSS = 5f
  suppressionFiles = listOf("dps-gradle-spring-boot-suppressions.xml")
  format = "ALL"
  analyzers.assemblyEnabled = false
}

ktlint {
  version.set("1.5.0")
}
