plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "8.3.2"
  kotlin("plugin.spring") version "2.2.0"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
  implementation("io.opentelemetry:opentelemetry-extension-kotlin")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

  implementation(project(":hmpps-sqs-spring-boot-starter"))

  testImplementation("org.assertj:assertj-core:3.27.3")
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.3")
  testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:6.0.0")
  testImplementation("org.mockito:mockito-inline:5.2.0")
  testImplementation("org.awaitility:awaitility-kotlin:4.3.0")
  testImplementation("io.jsonwebtoken:jjwt-impl:0.12.6")
  testImplementation("io.jsonwebtoken:jjwt-jackson:0.12.6")
  testImplementation("org.wiremock:wiremock-standalone:3.13.1")
  testImplementation("org.testcontainers:localstack:1.21.3")
  testImplementation("com.amazonaws:aws-java-sdk-core:1.12.788") // Needed so Localstack has access to the AWS SDK V1 API
  testImplementation("com.google.code.gson:gson:2.13.1")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
  testImplementation("io.opentelemetry:opentelemetry-extension-trace-propagators")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

kotlin {
  jvmToolchain(21)
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
  }
}

configure<com.gorylenko.GitPropertiesPluginExtension> {
  dotGitDirectory.set(File("${project.rootDir}/.git"))
}
