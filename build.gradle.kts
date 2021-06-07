plugins {
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
}

allprojects {
  group = "uk.gov.justice.service.hmpps"
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
