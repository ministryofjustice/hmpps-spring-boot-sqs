package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.model.GetQueueAttributesRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult
import com.amazonaws.services.sqs.model.QueueAttributeName.All
import com.amazonaws.services.sqs.model.QueueAttributeName.ApproximateNumberOfMessages
import com.amazonaws.services.sqs.model.QueueAttributeName.ApproximateNumberOfMessagesNotVisible
import com.amazonaws.services.sqs.model.QueueAttributeName.RedrivePolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.Health.Builder
import org.springframework.boot.actuate.health.HealthIndicator
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success

class HmppsQueueHealth(private val hmppsQueue: HmppsQueue) : HealthIndicator {

  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  override fun health(): Health = buildHealth(checkQueueHealth(), checkDlqHealth())

  @JvmInline
  private value class HealthDetail(private val detail: Pair<String, String>) {
    fun key() = detail.first
    fun value() = detail.second
  }

  private fun checkQueueHealth(): List<Result<HealthDetail>> {
    val results = mutableListOf<Result<HealthDetail>>()
    results += success(HealthDetail("queueName" to hmppsQueue.queueName))

    getQueueAttributes().map { attributesResult ->
      results += success(HealthDetail("messagesOnQueue" to """${attributesResult.attributes[ApproximateNumberOfMessages.toString()]}"""))
      results += success(HealthDetail("messagesInFlight" to """${attributesResult.attributes[ApproximateNumberOfMessagesNotVisible.toString()]}"""))
      attributesResult.attributes["$RedrivePolicy"] ?: { results += failure(MissingRedrivePolicyException()) }
    }.onFailure { throwable -> results += failure(throwable) }

    return results.toList()
  }

  private fun checkDlqHealth(): List<Result<HealthDetail>> {
    val results = mutableListOf<Result<HealthDetail>>()
    results += success(HealthDetail("dlqName" to hmppsQueue.dlqName))

    getDlqAttributes().map { attributesResult ->
      results += success(HealthDetail("messagesOnDlq" to """${attributesResult.attributes[ApproximateNumberOfMessages.toString()]}"""))
    }.onFailure { throwable -> results += failure(throwable) }

    return results.toList()
  }

  private fun buildHealth(queueResults: List<Result<HealthDetail>>, dlqResults: List<Result<HealthDetail>>): Health {
    val healthBuilder = if (queueStatus(dlqResults, queueResults) == "UP") Builder().up() else Builder().down()
    queueResults.forEach { healthBuilder.addHealthResult(it) }

    healthBuilder.withDetail("dlqStatus", dlqStatus(dlqResults, queueResults))
    dlqResults.forEach { healthBuilder.addHealthResult(it) }

    return healthBuilder.build()
  }

  private fun queueStatus(dlqResults: List<Result<HealthDetail>>, queueResults: List<Result<HealthDetail>>): String =
    if ((queueResults + dlqResults).any { it.isFailure }) "DOWN" else "UP"

  private fun dlqStatus(dlqResults: List<Result<HealthDetail>>, queueResults: List<Result<HealthDetail>>): String =
    if (queueResults.any(::isMissingRedrivePolicy).or(dlqResults.any { it.isFailure })) "DOWN" else "UP"

  private fun isMissingRedrivePolicy(result: Result<HealthDetail>) = result.exceptionOrNull() is MissingRedrivePolicyException

  private fun Builder.addHealthResult(result: Result<HealthDetail>) =
    result
      .onSuccess { healthDetail -> withDetail(healthDetail.key(), healthDetail.value()) }
      .onFailure { throwable -> withException(throwable) }

  private fun getQueueAttributes(): Result<GetQueueAttributesResult> {
    return runCatching {
      hmppsQueue.sqsAwsClient.getQueueAttributes(GetQueueAttributesRequest(hmppsQueue.queueUrl).withAttributeNames(All))
    }
  }

  private fun getDlqAttributes(): Result<GetQueueAttributesResult> =
    runCatching {
      hmppsQueue.sqsAwsDlqClient.getQueueAttributes(GetQueueAttributesRequest(hmppsQueue.dlqUrl).withAttributeNames(All))
    }
}

class MissingRedrivePolicyException : RuntimeException("The queue is missing a $RedrivePolicy")
