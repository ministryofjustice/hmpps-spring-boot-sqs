plugins {
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
  kotlin("jvm") version "2.2.21" apply false
}

/*
 * This build script only handles publishing to the Sonatype Nexus repository (a.k.a. Maven Central).
 *
 * The build scripts that produce the published autoconfigure and starter libraries can be found at `/hmpps-sqs-spring-boot-autoconfigure/build.gradle.kts` and `/hmpps-sqs-spring-boot-starter/build.gradle.kts`.
 */

allprojects {
  group = "uk.gov.justice.service.hmpps"
  version = "5.6.3"
  repositories {
    mavenCentral()
  }
}

nexusPublishing {
  repositories {
    create("sonatype") {
      nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
      snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
      username.set(System.getenv("OSSRH_USERNAME"))
      password.set(System.getenv("OSSRH_PASSWORD"))
    }
  }
}
