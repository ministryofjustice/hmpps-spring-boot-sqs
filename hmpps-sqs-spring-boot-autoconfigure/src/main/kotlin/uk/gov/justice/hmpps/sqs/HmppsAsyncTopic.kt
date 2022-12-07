package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sns.SnsAsyncClient

class HmppsAsyncTopic(
  val id: String,
  val arn: String,
  val snsClient: SnsAsyncClient,
)
