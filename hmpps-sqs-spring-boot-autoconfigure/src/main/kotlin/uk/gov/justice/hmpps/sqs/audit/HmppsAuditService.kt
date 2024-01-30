package uk.gov.justice.hmpps.sqs.audit

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import uk.gov.justice.hmpps.sqs.AUDIT_ID
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import java.time.Instant

open class HmppsAuditService(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
) {

  private val auditQueue by lazy { hmppsQueueService.findByQueueId(AUDIT_ID) as HmppsQueue }
  private val auditSqsClient by lazy { auditQueue.sqsClient }
  private val auditQueueUrl by lazy { auditQueue.queueUrl }

  open suspend fun publishEvent(hmppsAuditEvent: HmppsAuditEvent): SendMessageResponse =
    auditSqsClient.sendMessage(
      SendMessageRequest.builder()
        .queueUrl(auditQueueUrl)
        .messageBody(objectMapper.writeValueAsString(hmppsAuditEvent))
        .build(),
    ).await().also {
      log.debug("Published audit event with message id {}", it.messageId())
    }

  private companion object {
    private val log: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

data class HmppsAuditEvent(
  val what: String,
  val subjectId: String? = null,
  val subjectType: String? = null,
  val correlationId: String? = null,
  val `when`: Instant = Instant.now(),
  val who: String,
  val service: String,
  val details: String? = null,
)
