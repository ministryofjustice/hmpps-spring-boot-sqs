package uk.gov.justice.hmpps.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

const val LOCALSTACK_ARN_PREFIX = "arn:aws:sns:eu-west-2:000000000000:"

@ConstructorBinding
@ConfigurationProperties(prefix = "hmpps.sqs")
data class HmppsSqsProperties(
  val provider: String = "aws",
  val region: String = "eu-west-2",
  val localstackUrl: String = "http://localhost:4566",
  val queues: Map<String, QueueConfig>,
  val topics: Map<String, TopicConfig> = mapOf(),
) {
  data class QueueConfig(
    val queueName: String,
    val queueAccessKeyId: String = "",
    val queueSecretAccessKey: String = "",
    val asyncQueueClient: Boolean = false,
    val subscribeTopicId: String = "",
    val subscribeFilter: String = "",
    val dlqName: String,
    val dlqAccessKeyId: String = "",
    val dlqSecretAccessKey: String = "",
    val asyncDlqClient: Boolean = false,
  )

  data class TopicConfig(
    val arn: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val asyncClient: Boolean = false,
  ) {
    val name
      get() = if (arn.startsWith(LOCALSTACK_ARN_PREFIX)) arn.removePrefix(LOCALSTACK_ARN_PREFIX) else "We only provide a topic name for localstack"
  }

  init {
    queues.forEach { (queueId, queueConfig) ->
      if (queueId != queueId.lowercase()) throw InvalidHmppsSqsPropertiesException("queueId $queueId is not lowercase")
      if (provider == "aws") {
        if (queueConfig.queueAccessKeyId.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a queue access key id")
        if (queueConfig.queueSecretAccessKey.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a queue secret access key")
        if (queueConfig.dlqAccessKeyId.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a DLQ access key id")
        if (queueConfig.dlqSecretAccessKey.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a DLQ secret access key")
      }
      if (provider == "localstack") {
        if (queueConfig.subscribeTopicId.isNotEmpty().and(topics.containsKey(queueConfig.subscribeTopicId).not()))
          throw InvalidHmppsSqsPropertiesException("queueId $queueId wants to subscribe to ${queueConfig.subscribeTopicId} but it does not exist")
      }
    }
    topics.forEach { (topicId, topicConfig) ->
      if (provider == "aws") {
        if (topicConfig.accessKeyId.isEmpty()) throw InvalidHmppsSqsPropertiesException("topicId $topicId does not have an access key id")
        if (topicConfig.secretAccessKey.isEmpty()) throw InvalidHmppsSqsPropertiesException("topicId $topicId does not have a secret access key")
      }
    }
  }
}

class InvalidHmppsSqsPropertiesException(message: String) : IllegalStateException(message)
