plugins {
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

/*
 * This build script only handles publishing to the Sonatype Nexus repository (a.k.a. Maven Central).
 *
 * The build scripts that produce the published autoconfigure and starter libraries can be found at `/hmpps-sqs-spring-boot-autoconfigure/build.gradle.kts` and `/hmpps-sqs-spring-boot-starter/build.gradle.kts`.
 */

allprojects {
  group = "uk.gov.justice.service.hmpps"
  version = "0.8.2-beta-2"
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
