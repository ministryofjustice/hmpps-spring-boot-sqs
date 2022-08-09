package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest

class HmppsAsyncQueue(
  val id: String,
  val sqsAsyncClient: SqsAsyncClient,
  val queueName: String,
  val sqsAsyncDlqClient: SqsAsyncClient? = null,
  val dlqName: String? = null
) {
  val queueUrl: String by lazy { sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).get().queueUrl() }
  val dlqUrl: String? by lazy { sqsAsyncDlqClient?.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())?.get()?.queueUrl() }
}
