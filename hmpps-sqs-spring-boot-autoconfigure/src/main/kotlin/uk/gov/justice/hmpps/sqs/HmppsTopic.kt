package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.sns.SnsClient

class HmppsTopic(
  val id: String,
  val arn: String,
  val snsClient: SnsClient,
)
