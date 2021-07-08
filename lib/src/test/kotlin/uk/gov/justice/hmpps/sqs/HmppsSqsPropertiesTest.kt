package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig

class HmppsSqsPropertiesTest {

  @Nested
  inner class GeneralRules {

    @Test
    fun `should not allow lowercase queueId`() {
      assertThatThrownBy {
        HmppsSqsProperties(queues = mapOf("notLowerCaseQueueId" to validAwsQueueConfig()))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("notLowerCaseQueueId")
        .hasMessageContaining("lowercase")
    }

    @Test
    fun `should not allow lowercase topicId`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf("queueid" to validAwsQueueConfig()),
          topics = mapOf("notLowerCaseTopicId" to validAwsTopicConfig())
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("notLowerCaseTopicId")
        .hasMessageContaining("lowercase")
    }

    @Test
    fun `should retrieve name for localstack topic`() {
      val properties = HmppsSqsProperties(
        provider = "localstack",
        queues = mapOf("queueid" to validLocalstackQueueConfig()),
        topics = mapOf("topicid" to validLocalstackTopicConfig().copy(arn = "${LOCALSTACK_ARN_PREFIX}topic-name"))
      )

      assertThat(properties.topics["topicid"]?.name).isEqualTo("topic-name")
    }
  }

  @Nested
  inner class AwsMandatoryProperties {
    @Test
    fun `should require a queue access key ID`() {
      assertThatThrownBy {
        HmppsSqsProperties(queues = mapOf("queueid" to validAwsQueueConfig().copy(queueAccessKeyId = "")))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("queueid")
        .hasMessageContaining("queue access key id")
    }

    @Test
    fun `should require a queue secret access key`() {
      assertThatThrownBy {
        HmppsSqsProperties(queues = mapOf("queueid" to validAwsQueueConfig().copy(queueSecretAccessKey = "")))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("queueid")
        .hasMessageContaining("queue secret access key")
    }

    @Test
    fun `should require a dlq access key ID`() {
      assertThatThrownBy {
        HmppsSqsProperties(queues = mapOf("queueid" to validAwsQueueConfig().copy(dlqAccessKeyId = "")))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("queueid")
        .hasMessageContaining("DLQ access key id")
    }

    @Test
    fun `should require a dlq secret access key`() {
      assertThatThrownBy {
        HmppsSqsProperties(queues = mapOf("queueid" to validAwsQueueConfig().copy(dlqSecretAccessKey = "")))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("queueid")
        .hasMessageContaining("DLQ secret access key")
    }

    @Test
    fun ` topics should have an arn`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf("queueid" to validAwsQueueConfig()),
          topics = mapOf("topicid" to validAwsTopicConfig().copy(arn = ""))
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("topicid")
        .hasMessageContaining("arn")
    }

    @Test
    fun `topics should have an access key id`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf("queueid" to validAwsQueueConfig()),
          topics = mapOf("topicid" to validAwsTopicConfig().copy(accessKeyId = ""))
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("topicid")
        .hasMessageContaining("access key id")
    }

    @Test
    fun `topics should have a secret access key`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf("queueid" to validAwsQueueConfig()),
          topics = mapOf("topicid" to validAwsTopicConfig().copy(secretAccessKey = ""))
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("topicid")
        .hasMessageContaining("secret access key")
    }
  }

  @Nested
  inner class LocalStackMandatoryProperties {

    @Test
    fun `topic name should exist`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          provider = "localstack",
          queues = mapOf("queueid" to validLocalstackQueueConfig()),
          topics = mapOf("topicid" to validLocalstackTopicConfig().copy(arn = LOCALSTACK_ARN_PREFIX))
        )
      }
    }

    @Test
    fun `topic should exist if subscribing to it`() {
      assertThatThrownBy {
        HmppsSqsProperties(provider = "localstack", queues = mapOf("queueid" to validLocalstackQueueConfig().copy(subscribeTopicId = "topicid")))
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("queueid")
        .hasMessageContaining("topicid")
        .hasMessageContaining("does not exist")
    }
  }

  @Nested
  inner class AwsDuplicateValues {

    @Test
    fun `queue names should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(queueName = "queue1"),
            "queueid2" to validAwsQueueConfig(2).copy(queueName = "queue2"),
            "queueid3" to validAwsQueueConfig(3).copy(queueName = "queue1")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated queue name")
        .hasMessageContaining("queue1")
        .hasMessageNotContaining("queue2")
    }

    @Test
    fun `access key ids should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(queueAccessKeyId = "key1"),
            "queueid2" to validAwsQueueConfig(2).copy(queueAccessKeyId = "key2"),
            "queueid3" to validAwsQueueConfig(3).copy(queueAccessKeyId = "key1"),
            "queueid4" to validAwsQueueConfig(4).copy(queueAccessKeyId = "key2"),
            "queueid5" to validAwsQueueConfig(5).copy(queueAccessKeyId = "key3")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated queue access key id")
        .hasMessageContaining("key1")
        .hasMessageContaining("key2")
        .hasMessageNotContaining("key3")
    }

    @Test
    fun `queue secret access keys should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(queueSecretAccessKey = "secret1"),
            "queueid2" to validAwsQueueConfig(2).copy(queueSecretAccessKey = "secret1"),
            "queueid3" to validAwsQueueConfig(3).copy(queueSecretAccessKey = "secret2")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated queue secret access keys")
        .hasMessageContaining("secret1")
        .hasMessageNotContaining("secret2")
    }

    @Test
    fun `dlq names should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(dlqName = "dlq1"),
            "queueid2" to validAwsQueueConfig(2).copy(dlqName = "dlq2"),
            "queueid3" to validAwsQueueConfig(3).copy(dlqName = "dlq2")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated dlq name")
        .hasMessageContaining("dlq2")
        .hasMessageNotContaining("dlq1")
    }

    @Test
    fun `dlq access key ids should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(dlqAccessKeyId = "key1"),
            "queueid2" to validAwsQueueConfig(2).copy(dlqAccessKeyId = "key2"),
            "queueid3" to validAwsQueueConfig(3).copy(dlqAccessKeyId = "key1"),
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated dlq access key id")
        .hasMessageContaining("key1")
        .hasMessageNotContaining("key3")
    }

    @Test
    fun `dlq secret access keys should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(
            "queueid1" to validAwsQueueConfig(1).copy(dlqSecretAccessKey = "secret1"),
            "queueid2" to validAwsQueueConfig(2).copy(dlqSecretAccessKey = "secret1"),
            "queueid3" to validAwsQueueConfig(3).copy(dlqSecretAccessKey = "secret2")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated dlq secret access keys")
        .hasMessageContaining("secret1")
        .hasMessageNotContaining("secret2")
    }

    @Test
    fun `topic arns should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(),
          topics = mapOf(
            "topic1" to validAwsTopicConfig(1).copy(arn = "arn1"),
            "topic2" to validAwsTopicConfig(2).copy(arn = "arn2"),
            "topic3" to validAwsTopicConfig(3).copy(arn = "arn1")
          )
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated topic arns")
        .hasMessageContaining("arn1")
        .hasMessageNotContaining("arn2")
    }

    @Test
    fun `topic access key ids should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(),
          topics = mapOf(
            "topic1" to validAwsTopicConfig(1).copy(accessKeyId = "key1"),
            "topic2" to validAwsTopicConfig(2).copy(accessKeyId = "key2"),
            "topic3" to validAwsTopicConfig(3).copy(accessKeyId = "key1")
          )
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated topic access key ids")
        .hasMessageContaining("key1")
        .hasMessageNotContaining("key2")
    }

    @Test
    fun `topic secret access keys should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          queues = mapOf(),
          topics = mapOf(
            "topic1" to validAwsTopicConfig(1).copy(secretAccessKey = "secret1"),
            "topic2" to validAwsTopicConfig(2).copy(secretAccessKey = "secret2"),
            "topic3" to validAwsTopicConfig(3).copy(secretAccessKey = "secret1")
          )
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated topic secret access keys")
        .hasMessageContaining("secret1")
        .hasMessageNotContaining("secret2")
    }
  }

  @Nested
  inner class LocalStackDuplicateValues {

    @Test
    fun `queue names should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          provider = "localstack",
          queues = mapOf(
            "queueid1" to validLocalstackQueueConfig(1).copy(queueName = "queue1"),
            "queueid2" to validLocalstackQueueConfig(2).copy(queueName = "queue2"),
            "queueid3" to validLocalstackQueueConfig(3).copy(queueName = "queue1")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated queue name")
        .hasMessageContaining("queue1")
        .hasMessageNotContaining("queue2")
    }

    @Test
    fun `dlq names should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          provider = "localstack",
          queues = mapOf(
            "queueid1" to validLocalstackQueueConfig(1).copy(dlqName = "dlq1"),
            "queueid2" to validLocalstackQueueConfig(2).copy(dlqName = "dlq2"),
            "queueid3" to validLocalstackQueueConfig(3).copy(dlqName = "dlq2")
          ),
          topics = mapOf()
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated dlq name")
        .hasMessageContaining("dlq2")
        .hasMessageNotContaining("dlq1")
    }

    @Test
    fun `topic names should be unique`() {
      assertThatThrownBy {
        HmppsSqsProperties(
          provider = "localstack",
          queues = mapOf(),
          topics = mapOf(
            "topic1" to validLocalstackTopicConfig(1).copy(arn = "${LOCALSTACK_ARN_PREFIX}name1"),
            "topic2" to validLocalstackTopicConfig(2).copy(arn = "${LOCALSTACK_ARN_PREFIX}name2"),
            "topic3" to validLocalstackTopicConfig(3).copy(arn = "${LOCALSTACK_ARN_PREFIX}name1")
          )
        )
      }.isInstanceOf(InvalidHmppsSqsPropertiesException::class.java)
        .hasMessageContaining("Found duplicated topic names")
        .hasMessageContaining("name1")
        .hasMessageNotContaining("name2")
    }
  }

  private fun validAwsQueueConfig(index: Int = 1) = QueueConfig(queueName = "name$index", queueAccessKeyId = "key$index", queueSecretAccessKey = "secret$index", dlqName = "dlqName$index", dlqAccessKeyId = "dlqKey$index", dlqSecretAccessKey = "dlqSecret$index")
  private fun validAwsTopicConfig(index: Int = 1) = TopicConfig(arn = "arn$index", accessKeyId = "key$index", secretAccessKey = "secret$index")
  private fun validLocalstackQueueConfig(index: Int = 1) = QueueConfig(queueName = "name$index", dlqName = "dlqName$index", dlqAccessKeyId = "dlqKey$index", dlqSecretAccessKey = "dlqSecret$index")
  private fun validLocalstackTopicConfig(index: Int = 1) = TopicConfig(arn = "${LOCALSTACK_ARN_PREFIX}$index")
}
