plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "4.8.6"
  kotlin("plugin.spring") version "1.8.21"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  implementation("org.springdoc:springdoc-openapi-ui:1.7.0")
  implementation("org.springdoc:springdoc-openapi-webflux-ui:1.7.0")
  implementation("org.springdoc:springdoc-openapi-kotlin:1.7.0")
  implementation("org.springdoc:springdoc-openapi-security:1.7.0")

  implementation(project(":hmpps-sqs-spring-boot-starter"))

  testImplementation("org.assertj:assertj-core:3.24.2")
  testImplementation("org.junit.jupiter:junit-jupiter:5.9.3")
  testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")
  testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
  testImplementation("org.mockito:mockito-inline:5.2.0")
  testImplementation("org.awaitility:awaitility-kotlin:4.2.0")
  testImplementation("io.jsonwebtoken:jjwt:0.9.1")
  testImplementation("com.github.tomakehurst:wiremock-standalone:2.27.2")
  testImplementation("org.testcontainers:localstack:1.18.1")
}
