plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "3.3.12"
  kotlin("plugin.spring") version "1.5.21"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  implementation(project(":hmpps-sqs-spring-boot-starter"))

  testImplementation("org.assertj:assertj-core:3.21.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
  testImplementation("org.mockito:mockito-junit-jupiter:4.0.0")
  testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
  testImplementation("org.mockito:mockito-inline:4.0.0")
  testImplementation("org.awaitility:awaitility-kotlin:4.1.1")
  testImplementation("io.jsonwebtoken:jjwt:0.9.1")
  testImplementation("com.github.tomakehurst:wiremock-standalone:2.27.2")
  testImplementation("org.testcontainers:localstack:1.16.2")
}
