plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "3.3.0"
  kotlin("plugin.spring") version "1.5.10"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
  implementation("org.springframework.boot:spring-boot-starter-webflux")

  implementation(project(":lib"))
  implementation(platform("com.amazonaws:aws-java-sdk-bom:1.11.942"))
  implementation("com.amazonaws:aws-java-sdk-sqs:1.11.106")
  implementation("org.springframework:spring-jms")

  testImplementation("org.assertj:assertj-core:3.19.0")
  testImplementation("org.junit.jupiter:junit-jupiter:5.8.0-M1")
  testImplementation("org.mockito:mockito-junit-jupiter:3.10.0")
  testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.2.0")
  testImplementation("org.mockito:mockito-inline:3.10.0")
  testImplementation("org.awaitility:awaitility-kotlin:4.1.0")
}

tasks {
  compileKotlin {
    kotlinOptions {
      jvmTarget = "16"
    }
  }
}
