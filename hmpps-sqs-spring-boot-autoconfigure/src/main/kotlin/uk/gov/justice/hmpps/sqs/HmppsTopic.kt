package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sns.SnsAsyncClient

class HmppsTopic(
  val id: String,
  val arn: String,
  val snsClient: SnsAsyncClient
)
