package uk.gov.justice.hmpps.sqs

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName

class HmppsQueue(
  val id: String,
  val sqsClient: SqsAsyncClient,
  val queueName: String,
  val sqsDlqClient: SqsAsyncClient? = null,
  val dlqName: String? = null,
) {
  val queueUrl: String by lazy {
    sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).get().queueUrl()
  }

  val queueArn: String? by lazy {
    getArn(sqsClient, queueUrl)
  }

  val dlqUrl: String? by lazy {
    sqsDlqClient?.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())?.get()?.queueUrl()
  }

  val dlqArn: String? by lazy {
    sqsDlqClient?.let { c ->
      dlqUrl?.let { url -> getArn(c, url) }
    }
  }

  val maxReceiveCount: Int? by lazy {
    if (dlqName != null) {
      runCatching {
        getMaxReceiveCount(sqsClient, queueUrl)
      }
        .onFailure { log.error("Unable to retrieve maxReceiveCount for queue $queueName", it) }
        .getOrNull()
    } else {
      null
    }
  }

  private fun getArn(client: SqsAsyncClient, url: String) = client.getQueueAttributes(getArnAttribute(url)).get().attributes()[QueueAttributeName.QUEUE_ARN]

  private fun getArnAttribute(url: String) = GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN).build()

  private fun getMaxReceiveCount(client: SqsAsyncClient, url: String) = client.getQueueAttributes(getRedrivePolicyAttribute(url)).get().attributes()[QueueAttributeName.REDRIVE_POLICY]
    .let { gson.fromJson(it, Map::class.java)["maxReceiveCount"].toString().toInt() }

  private fun getRedrivePolicyAttribute(url: String) = GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.REDRIVE_POLICY).build()

  private companion object {
    val log = LoggerFactory.getLogger(this::class.java)
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
  }
}

