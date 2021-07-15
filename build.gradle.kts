plugins {
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

/*
 * This build script only handles publishing to the Sonatype Nexus repository (a.k.a. Maven Central).
 *
 * The build script that produces the published library can be found at `/hmpps-sqs-spring-boot-autoconfigure/build.gradle.kts`.
 */

allprojects {
  group = "uk.gov.justice.service.hmpps"
  version = "0.7.1"
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
