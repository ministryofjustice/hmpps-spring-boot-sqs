package uk.gov.justice.hmpps.sqs

import software.amazon.awssdk.services.s3.S3AsyncClient

class HmppsS3Bucket(
  val name: String,
  val client: S3AsyncClient
)