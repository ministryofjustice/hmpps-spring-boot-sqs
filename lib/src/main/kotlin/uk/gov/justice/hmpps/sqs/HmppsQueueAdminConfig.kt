package uk.gov.justice.hmpps.sqs

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class HmppsQueueAdminConfig() {

  @Value("\${hmpps.sqs.queueAdminRole")
  private lateinit var queueAdminRole: String
}
