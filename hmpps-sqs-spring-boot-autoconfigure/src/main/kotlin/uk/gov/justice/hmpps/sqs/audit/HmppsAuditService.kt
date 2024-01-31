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

/**
 * Helper service to send a message to the audit queue.
 *
 * This service will be wired up by spring boot automatically if an audit queue with id `audit` is defined.
 *
 * The service will also default the service name to the `spring.application.name`.  It can be, if required, passed into the audit method instead.
 */
open class HmppsAuditService(
  private val hmppsQueueService: HmppsQueueService,
  private val objectMapper: ObjectMapper,
  private val applicationName: String?,
) {
  private val auditQueue by lazy { hmppsQueueService.findByQueueId(AUDIT_ID) as HmppsQueue }
  private val auditSqsClient by lazy { auditQueue.sqsClient }
  private val auditQueueUrl by lazy { auditQueue.queueUrl }

  /**
   * Publish an event to the HMPPS Audit queue.
   */
  open suspend fun publishEvent(hmppsAuditEvent: HmppsAuditEvent): SendMessageResponse =
    auditSqsClient.sendMessage(
      SendMessageRequest.builder()
        .queueUrl(auditQueueUrl)
        .messageBody(objectMapper.writeValueAsString(hmppsAuditEvent))
        .build(),
    ).await().also {
      log.debug("Published audit event with message id {}", it.messageId())
    }

  /**
   * Publish an event to the HMPPS Audit queue.
   *
   * This version defaults the service parameter to the `spring.application.name`.
   *
   * Also, in the same way as for `HmppsAuditEvent`, the `when` variable is also defaulted to `now`.
   */
  open suspend fun publishEvent(
    what: String,
    subjectId: String? = null,
    subjectType: String? = null,
    correlationId: String? = null,
    `when`: Instant = Instant.now(),
    who: String,
    service: String? = applicationName,
    details: String? = null,
  ): SendMessageResponse = publishEvent(
    HmppsAuditEvent(
      what = what,
      subjectId = subjectId,
      subjectType = subjectType,
      correlationId = correlationId,
      `when` = `when`,
      who = who,
      service = service!!,
      details = details,
    ),
  )

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
