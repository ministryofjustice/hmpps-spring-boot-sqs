package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.GetQueueUrlResult
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString

class HmppsQueueServiceTest {

  private val sqsAwsClient = mock<AmazonSQS>()
  private val sqsAwsDlqClient = mock<AmazonSQS>()
  private val hmppsQueueService = HmppsQueueService()

  @BeforeEach
  fun `add test data`() {
    whenever(sqsAwsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
    whenever(sqsAwsDlqClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
    hmppsQueueService.addHmppsQueue(HmppsQueue(sqsAwsClient, "some queue name", sqsAwsDlqClient, "some dlq name"))
    hmppsQueueService.addHmppsQueue(HmppsQueue(mock(), "another queue name", mock(), "another dlq name"))
  }

  @Test
  fun `finds an hmpps queue by queue name`() {
    assertThat(hmppsQueueService.findByQueueName("some queue name")?.queueUrl).isEqualTo("some queue url")
  }

  @Test
  fun `finds an hmpps queue by dlq name`() {
    assertThat(hmppsQueueService.findByDlqName("some dlq name")?.dlqUrl).isEqualTo("some dlq url")
  }

  @Test
  fun `returns null if queue not found`() {
    assertThat(hmppsQueueService.findByQueueName("unknown")).isNull()
  }

  @Test
  fun `returns null if dlq not found`() {
    assertThat(hmppsQueueService.findByDlqName("unknown")).isNull()
  }
}
