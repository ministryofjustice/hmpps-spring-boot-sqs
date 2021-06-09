package uk.gov.justice.hmpps.sqs

import org.springframework.stereotype.Service

@Service
class HmppsQueueService {

  private val hmppsQueues: MutableList<HmppsQueue> = mutableListOf()

  fun findByQueueName(queueName: String) = hmppsQueues.associateBy { it.queueName }.getOrDefault(queueName, null)
  fun findByDlqName(dlqName: String) = hmppsQueues.associateBy { it.dlqName }.getOrDefault(dlqName, null)

  fun addHmppsQueue(hmppsQueue: HmppsQueue) {
    hmppsQueues += hmppsQueue
  }
}
