package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HmppsSqsPropertiesTest {

  @Test
  fun `should not allow lowercase queueId`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("notLowerCaseQueueId" to HmppsSqsProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("notLowerCaseQueueId")
      .hasMessageContaining("lowercase")
  }

  @Test
  fun `should require a queue access key ID`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue access key id")
  }

  @Test
  fun `should require a queue secret access key`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue secret access key")
  }

  @Test
  fun `should require a dlq access key ID`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ access key id")
  }

  @Test
  fun `should require a dlq secret access key`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any")))
    }.isInstanceOf(InvalidHmppsQueuePropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ secret access key")
  }
}
