package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS

class HmppsQueue(
  val id: String,
  val sqsClient: AmazonSQS,
  val queueName: String,
  val sqsDlqClient: AmazonSQS,
  val dlqName: String,
) {
  val queueUrl: String by lazy { sqsClient.getQueueUrl(queueName).queueUrl }
  val dlqUrl: String by lazy { sqsDlqClient.getQueueUrl(dlqName).queueUrl }
}
