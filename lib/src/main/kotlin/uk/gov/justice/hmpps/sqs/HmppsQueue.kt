package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS

class HmppsQueue(
  val sqsAwsClient: AmazonSQS,
  val queueName: String,
  val sqsAwsDlqClient: AmazonSQS,
  val dlqName: String,
) {
  val queueUrl: String by lazy { sqsAwsClient.getQueueUrl(queueName).queueUrl }
  val dlqUrl: String by lazy { sqsAwsDlqClient.getQueueUrl(dlqName).queueUrl }
}
