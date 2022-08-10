plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "4.7.1"
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

  implementation("org.springdoc:springdoc-openapi-ui:1.6.12")
  implementation("org.springdoc:springdoc-openapi-webflux-ui:1.6.12")
  implementation("org.springdoc:springdoc-openapi-kotlin:1.6.12")
  implementation("org.springdoc:springdoc-openapi-security:1.6.12")

  implementation(project(":hmpps-sqs-spring-boot-starter"))

  testImplementation("org.assertj:assertj-core:3.23.1")
  testImplementation("org.junit.jupiter:junit-jupiter:5.9.1")
  testImplementation("org.mockito:mockito-junit-jupiter:4.8.1")
  testImplementation("org.mockito.kotlin:mockito-kotlin:4.0.0")
  testImplementation("org.mockito:mockito-inline:4.8.1")
  testImplementation("org.awaitility:awaitility-kotlin:4.2.0")
  testImplementation("io.jsonwebtoken:jjwt:0.9.1")
  testImplementation("com.github.tomakehurst:wiremock-standalone:2.27.2")
  testImplementation("org.testcontainers:localstack:1.17.5")
  testImplementation("com.amazonaws:aws-java-sdk-core:1.12.272") // Needed so Localstack has access to the AWS SDK V1 API
}
