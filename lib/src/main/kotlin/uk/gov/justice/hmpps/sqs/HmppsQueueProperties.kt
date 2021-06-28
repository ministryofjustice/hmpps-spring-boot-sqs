package uk.gov.justice.hmpps.sqs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConstructorBinding
@ConfigurationProperties(prefix = "hmpps.sqs")
data class HmppsQueueProperties(
  val provider: String = "aws",
  val region: String = "eu-west-2",
  val localstackUrl: String = "http://localhost:4566",
  val queues: Map<String, QueueConfig>,
) {
  data class QueueConfig(
    val queueName: String,
    val queueAccessKeyId: String = "",
    val queueSecretAccessKey: String = "",
    val dlqName: String,
    val dlqAccessKeyId: String = "",
    val dlqSecretAccessKey: String = "",
    val asyncClient: Boolean = false,
  )
}
