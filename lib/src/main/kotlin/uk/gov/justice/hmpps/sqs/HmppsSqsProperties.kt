package uk.gov.justice.hmpps.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = "hmpps.sqs")
data class HmppsSqsProperties(
  val provider: String = "aws",
  val region: String = "eu-west-2",
  val localstackUrl: String = "http://localhost:4566",
  val queues: Map<String, QueueConfig>,
) {
  data class QueueConfig(
    val queueName: String,
    val queueAccessKeyId: String = "",
    val queueSecretAccessKey: String = "",
    val asyncQueueClient: Boolean = false,
    val dlqName: String,
    val dlqAccessKeyId: String = "",
    val dlqSecretAccessKey: String = "",
    val asyncDlqClient: Boolean = false,
  )

  init {
    queues.forEach { (queueId, queueConfig) ->
      if (queueId != queueId.lowercase()) throw InvalidHmppsSqsPropertiesException("queueId $queueId is not lowercase")
      if (provider == "aws") {
        if (queueConfig.queueAccessKeyId.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a queue access key id")
        if (queueConfig.queueSecretAccessKey.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a queue secret access key")
        if (queueConfig.dlqAccessKeyId.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a DLQ access key id")
        if (queueConfig.dlqSecretAccessKey.isEmpty()) throw InvalidHmppsSqsPropertiesException("queueId $queueId does not have a DLQ secret access key")
      }
    }
  }
}

class InvalidHmppsSqsPropertiesException(message: String) : IllegalStateException(message)
