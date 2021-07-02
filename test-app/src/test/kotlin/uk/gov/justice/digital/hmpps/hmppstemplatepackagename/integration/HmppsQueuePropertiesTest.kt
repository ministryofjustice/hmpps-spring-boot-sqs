package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HmppsQueuePropertiesTest : IntegrationTestBase() {

  @Test
  fun `should load the main queue properties`() {
    assertThat(hmppsQueueProperties.provider).isEqualTo("localstack")
    assertThat(hmppsQueueProperties.localstackUrl).isEqualTo("http://localhost:4566")
    assertThat(hmppsQueueProperties.region).isEqualTo("eu-west-2")
    assertThat(hmppsQueueProperties.mainQueueConfig().queueName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.mainQueueConfig().queueAccessKeyId).isEqualTo("queue-access-key-id")
    assertThat(hmppsQueueProperties.mainQueueConfig().queueSecretAccessKey).isEqualTo("queue-secret-access-key")
    assertThat(hmppsQueueProperties.mainQueueConfig().dlqName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.mainQueueConfig().dlqAccessKeyId).isEqualTo("dlq-access-key-id")
    assertThat(hmppsQueueProperties.mainQueueConfig().dlqSecretAccessKey).isEqualTo("dlq-secret-access-key")
  }

  @Test
  fun `should load the another queue properties`() {
    assertThat(hmppsQueueProperties.provider).isEqualTo("localstack")
    assertThat(hmppsQueueProperties.localstackUrl).isEqualTo("http://localhost:4566")
    assertThat(hmppsQueueProperties.region).isEqualTo("eu-west-2")
    assertThat(hmppsQueueProperties.anotherQueueConfig().queueName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.anotherQueueConfig().queueAccessKeyId).isEqualTo("another-queue-access-key-id")
    assertThat(hmppsQueueProperties.anotherQueueConfig().queueSecretAccessKey).isEqualTo("another-queue-secret-access-key")
    assertThat(hmppsQueueProperties.anotherQueueConfig().dlqName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.anotherQueueConfig().dlqAccessKeyId).isEqualTo("another-dlq-access-key-id")
    assertThat(hmppsQueueProperties.anotherQueueConfig().dlqSecretAccessKey).isEqualTo("another-dlq-secret-access-key")
  }
}
