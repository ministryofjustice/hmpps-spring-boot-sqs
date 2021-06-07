package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.resource

import com.amazonaws.services.sqs.AmazonSQS
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.SqsConfigProperties
import uk.gov.justice.hmpps.sqs.SqsQueueAdminService
import uk.gov.justice.hmpps.sqs.TransferMessagesRequest

@RestController
@RequestMapping("/queues", produces = [MediaType.APPLICATION_JSON_VALUE])
class QueueAdminResource(
  private val sqsQueueAdminService: SqsQueueAdminService,
  private val sqsClient: AmazonSQS,
  private val sqsDlqClient: AmazonSQS,
  private val sqsConfigProperties: SqsConfigProperties,
) {

  @PutMapping("/retry-dlq")
  fun retryDlqMessages() {
    val dlqUrl = sqsDlqClient.getQueueUrl(sqsConfigProperties.dlqName).queueUrl
    val queueUrl = sqsClient.getQueueUrl(sqsConfigProperties.queueName).queueUrl
    TransferMessagesRequest(sqsDlqClient, dlqUrl, sqsClient, queueUrl)
      .run {
        sqsQueueAdminService.transferAllMessages(this)
      }
  }
}
