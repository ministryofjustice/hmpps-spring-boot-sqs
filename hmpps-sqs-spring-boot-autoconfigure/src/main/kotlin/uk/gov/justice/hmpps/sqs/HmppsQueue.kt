package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest

class HmppsQueue(
  val id: String,
  val sqsClient: SqsAsyncClient,
  val queueName: String,
  val sqsDlqClient: SqsAsyncClient? = null,
  val dlqName: String? = null
) {
  val queueUrl: String by lazy { sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).get().queueUrl() }
  val dlqUrl: String? by lazy { sqsDlqClient?.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())?.get()?.queueUrl() }
}
