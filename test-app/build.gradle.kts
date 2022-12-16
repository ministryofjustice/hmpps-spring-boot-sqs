plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "5.0.0-beta"
  kotlin("plugin.spring") version "1.7.22"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.0.0")

  implementation(project(":hmpps-sqs-spring-boot-starter"))

  testImplementation("org.assertj:assertj-core:3.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
  testImplementation("org.mockito:mockito-junit-jupiter:4.9.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
  testImplementation("org.mockito:mockito-inline:4.10.0")
  testImplementation("org.awaitility:awaitility-kotlin:4.2.0")
  testImplementation("io.jsonwebtoken:jjwt-impl:0.11.5")
  testImplementation("io.jsonwebtoken:jjwt-jackson:0.11.5")
  testImplementation("com.github.tomakehurst:wiremock-jre8-standalone:2.35.0")
  testImplementation("org.testcontainers:localstack:1.17.6")
  testImplementation("com.amazonaws:aws-java-sdk-core:1.12.365") // Needed so Localstack has access to the AWS SDK V1 API
}
