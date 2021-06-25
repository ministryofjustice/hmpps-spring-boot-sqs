package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.anotherQueue
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.mainQueue

class HmppsQueuePropertiesTest : IntegrationTestBase() {

  @Test
  fun `should load the main queue properties`() {
    assertThat(hmppsQueueProperties.provider).isEqualTo("localstack")
    assertThat(hmppsQueueProperties.localstackUrl).isEqualTo("http://localhost:4566")
    assertThat(hmppsQueueProperties.region).isEqualTo("eu-west-2")
    assertThat(hmppsQueueProperties.mainQueue().queueName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.mainQueue().queueAccessKeyId).isEqualTo("queue-access-key-id")
    assertThat(hmppsQueueProperties.mainQueue().queueSecretAccessKey).isEqualTo("queue-secret-access-key")
    assertThat(hmppsQueueProperties.mainQueue().dlqName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.mainQueue().dlqAccessKeyId).isEqualTo("dlq-access-key-id")
    assertThat(hmppsQueueProperties.mainQueue().dlqSecretAccessKey).isEqualTo("dlq-secret-access-key")
  }

  @Test
  fun `should load the another queue properties`() {
    assertThat(hmppsQueueProperties.provider).isEqualTo("localstack")
    assertThat(hmppsQueueProperties.localstackUrl).isEqualTo("http://localhost:4566")
    assertThat(hmppsQueueProperties.region).isEqualTo("eu-west-2")
    assertThat(hmppsQueueProperties.anotherQueue().queueName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.anotherQueue().queueAccessKeyId).isEqualTo("another-queue-access-key-id")
    assertThat(hmppsQueueProperties.anotherQueue().queueSecretAccessKey).isEqualTo("another-queue-secret-access-key")
    assertThat(hmppsQueueProperties.anotherQueue().dlqName).isNotNull.isNotEmpty
    assertThat(hmppsQueueProperties.anotherQueue().dlqAccessKeyId).isEqualTo("another-dlq-access-key-id")
    assertThat(hmppsQueueProperties.anotherQueue().dlqSecretAccessKey).isEqualTo("another-dlq-secret-access-key")
  }
}
