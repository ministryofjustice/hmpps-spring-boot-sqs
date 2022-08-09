package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest

class HmppsQueue(
  val id: String,
  val sqsClient: SqsClient,
  val queueName: String,
  val sqsDlqClient: SqsClient? = null,
  val dlqName: String? = null
) {
  val queueUrl: String by lazy { sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl() }
  val dlqUrl: String? by lazy { sqsDlqClient?.getQueueUrl(GetQueueUrlRequest.builder().queueName(dlqName).build())?.queueUrl() }
}
