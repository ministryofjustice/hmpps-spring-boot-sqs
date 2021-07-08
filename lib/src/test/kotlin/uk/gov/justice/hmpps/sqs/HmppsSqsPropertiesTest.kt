package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HmppsSqsPropertiesTest {

  @Test
  fun `should not allow lowercase queueId`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("notLowerCaseQueueId" to HmppsSqsProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("notLowerCaseQueueId")
      .hasMessageContaining("lowercase")
  }

  @Test
  fun `should require a queue access key ID`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue access key id")
  }

  @Test
  fun `should require a queue secret access key`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("queue secret access key")
  }

  @Test
  fun `should require a dlq access key ID`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ access key id")
  }

  @Test
  fun `should require a dlq secret access key`() {
    assertThatThrownBy {
      HmppsSqsProperties(queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("DLQ secret access key")
  }

  @Test
  fun `topic should exist if subscribing to it`() {
    assertThatThrownBy {
      HmppsSqsProperties(provider = "localstack", queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", dlqName = "any", subscribeTopicId = "topicid")))
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("queueid")
      .hasMessageContaining("topicid")
      .hasMessageContaining("does not exist")
  }

  @Test
  fun `aws topics should have an access key id`() {
    assertThatThrownBy {
      HmppsSqsProperties(
        queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any", dlqSecretAccessKey = "any")),
        topics = mapOf("topicid" to HmppsSqsProperties.TopicConfig(arn = "any", secretAccessKey = "any"))
      )
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("topicid")
      .hasMessageContaining("access key id")
  }

  @Test
  fun `aws topics should have a secret access key`() {
    assertThatThrownBy {
      HmppsSqsProperties(
        queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any", dlqSecretAccessKey = "any")),
        topics = mapOf("topicid" to HmppsSqsProperties.TopicConfig(arn = "any", accessKeyId = "any"))
      )
    }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
      .hasMessageContaining("topicid")
      .hasMessageContaining("secret access key")
  }

  @Test
  fun `should retrieve name for localstack topic`() {
    val properties = HmppsSqsProperties(
      queues = mapOf("queueid" to HmppsSqsProperties.QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", dlqName = "any", dlqAccessKeyId = "any", dlqSecretAccessKey = "any")),
      topics = mapOf("topicid" to HmppsSqsProperties.TopicConfig(arn = "arn:aws:sns:eu-west-2:000000000000:topic-name", accessKeyId = "any", secretAccessKey = "any"))
    )

    assertThat(properties.topics["topicid"]?.name).isEqualTo("topic-name")
  }
}
