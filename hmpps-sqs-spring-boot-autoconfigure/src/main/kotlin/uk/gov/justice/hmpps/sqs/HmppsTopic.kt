package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sns.AmazonSNS

class HmppsTopic(
  val id: String,
  val arn: String,
  val snsClient: AmazonSNS,
)
