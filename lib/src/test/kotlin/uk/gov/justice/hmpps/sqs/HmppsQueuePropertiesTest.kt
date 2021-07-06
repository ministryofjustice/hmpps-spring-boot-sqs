package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HmppsQueuePropertiesTest {

  @Test
  fun `should not allow lowercase queueId`() {
    assertThatThrownBy {
      HmppsQueueProperties(queues = mapOf("notLowerCaseQueueId" to HmppsQueueProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("notLowerCaseQueueId")
      .hasMessageContaining("lowercase")
  }

  @Test
  fun `should require a queue access key ID`() {
    assertThatThrownBy {
      HmppsQueueProperties(queues = mapOf("queueid" to HmppsQueueProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue access key id")
  }

  @Test
  fun `should require a queue secret access key`() {
    assertThatThrownBy {
      HmppsQueueProperties(queues = mapOf("queueid" to HmppsQueueProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue secret access key")
  }

  @Test
  fun `should require a dlq access key ID`() {
    assertThatThrownBy {
      HmppsQueueProperties(queues = mapOf("queueid" to HmppsQueueProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ access key id")
  }

  @Test
  fun `should require a dlq secret access key`() {
    assertThatThrownBy {
      HmppsQueueProperties(queues = mapOf("queueid" to HmppsQueueProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ secret access key")
  }
}
