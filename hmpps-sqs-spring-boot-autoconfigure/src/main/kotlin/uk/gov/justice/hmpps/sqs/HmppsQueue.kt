package uk.gov.justice.hmpps.sqs

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
}

private fun getArn(client: SqsAsyncClient, url: String) =
  client.getQueueAttributes(getArnAttribute(url)).get().attributes()[QueueAttributeName.QUEUE_ARN]

private fun getArnAttribute(url: String) =
  GetQueueAttributesRequest.builder().queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN).build()
