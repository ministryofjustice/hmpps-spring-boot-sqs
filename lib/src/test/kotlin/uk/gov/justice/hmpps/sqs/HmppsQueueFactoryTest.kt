package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.GetQueueAttributesResult
import com.amazonaws.services.sqs.model.GetQueueUrlResult
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties.QueueConfig

class HmppsQueueFactoryTest {

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<AmazonSqsFactory>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, sqsFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create single AWS HmppsQueue` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsQueueProperties = HmppsQueueProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private val sqsDlqClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsAmazonSQS(any(), any(), any()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
        .thenReturn(sqsClient)
      whenever(sqsDlqClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsQueueProperties)
    }

    @Test
    fun `creates aws sqs dlq client from sqs factory`() {
      verify(sqsFactory).awsAmazonSQS("dlq access key id", "dlq secret access key", "eu-west-2")
    }

    @Test
    fun `creates aws sqs client from sqs factory`() {
      verify(sqsFactory).awsAmazonSQS("some access key id", "some secret access key", "eu-west-2")
    }

    @Test
    fun `should return the queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
    }

    @Test
    fun `should return the queue AmazonSQS client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should return the queue name`() {
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
    }

    @Test
    fun `should return the queue url`() {
      assertThat(hmppsQueues[0].queueUrl).isEqualTo("some queue url")
    }

    @Test
    fun `should return the dlq client`() {
      assertThat(hmppsQueues[0].sqsDlqClient).isEqualTo(sqsDlqClient)
    }

    @Test
    fun `should return the dlq name`() {
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should return the dlq url`() {
      assertThat(hmppsQueues[0].dlqUrl).isEqualTo("some dlq url")
    }

    @Test
    fun `should register a health indicator`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
    }

    @Test
    fun `should register the sqs client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
    }

    @Test
    fun `should register the sqs dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-dlq-client", sqsDlqClient)
    }

    @Test
    fun `should register the jms listener factory`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueJmsListenerContainerFactory>())
    }
  }

  @Nested
  inner class `Create single LocalStack HmppsQueue` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsQueueProperties = HmppsQueueProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private val sqsDlqClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localStackAmazonSQS(anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(sqsDlqClient.getQueueAttributes(anyString(), anyList())).thenReturn(GetQueueAttributesResult().withAttributes(mapOf("QueueArn" to "some dlq arn")))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsQueueProperties)
    }

    @Test
    fun `creates LocalStack sqs clients from sqs factory`() {
      verify(sqsFactory, times(2)).localStackAmazonSQS(localstackUrl = "http://localhost:4566", region = "eu-west-2")
    }

    @Test
    fun `should return the queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
    }

    @Test
    fun `should return the queue AmazonSQS client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should return the queue name`() {
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
    }

    @Test
    fun `should return the queue url`() {
      assertThat(hmppsQueues[0].queueUrl).isEqualTo("some queue url")
    }

    @Test
    fun `should return the dlq client`() {
      assertThat(hmppsQueues[0].sqsDlqClient).isEqualTo(sqsDlqClient)
    }

    @Test
    fun `should return the dlq name`() {
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should return the dlq url`() {
      assertThat(hmppsQueues[0].dlqUrl).isEqualTo("some dlq url")
    }

    @Test
    fun `should register a health indicator`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
    }

    @Test
    fun `should register the sqs client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
    }

    @Test
    fun `should register the sqs dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-dlq-client", sqsDlqClient)
    }

    @Test
    fun `should retrieve the dlq arn from the dlq client`() {
      verify(sqsDlqClient).getQueueAttributes("some dlq url", listOf("QueueArn"))
    }

    @Test
    fun `should create a queue with a redrive policy`() {
      verify(sqsClient).createQueue(
        check<CreateQueueRequest> {
          assertThat(it.attributes).containsEntry("RedrivePolicy", """{"deadLetterTargetArn":"some dlq arn","maxReceiveCount":"5"}""")
        }
      )
    }
  }

  @Nested
  inner class `Create multiple AWS HmppsQueues` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val anotherQueueConfig = QueueConfig(queueName = "another queue name", queueAccessKeyId = "another access key id", queueSecretAccessKey = "another secret access key", dlqName = "another dlq name", dlqAccessKeyId = "another dlq access key id", dlqSecretAccessKey = "another dlq secret access key")
    private val hmppsQueueProperties = HmppsQueueProperties(queues = mapOf("somequeueid" to someQueueConfig, "anotherqueueid" to anotherQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private val sqsDlqClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsAmazonSQS(any(), any(), any()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl("some dlq name")).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(sqsClient.getQueueUrl("another queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("another queue url"))
      whenever(sqsDlqClient.getQueueUrl("another dlq name")).thenReturn(GetQueueUrlResult().withQueueUrl("another dlq url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsQueueProperties)
    }

    @Test
    fun `should create multiple dlq clients from sqs factory`() {
      verify(sqsFactory).awsAmazonSQS("dlq access key id", "dlq secret access key", "eu-west-2")
      verify(sqsFactory).awsAmazonSQS("another dlq access key id", "another dlq secret access key", "eu-west-2")
    }

    @Test
    fun `should create multiple sqs clients from sqs factory`() {
      verify(sqsFactory).awsAmazonSQS("some access key id", "some secret access key", "eu-west-2")
      verify(sqsFactory).awsAmazonSQS("another access key id", "another secret access key", "eu-west-2")
    }

    @Test
    fun `should return multiple queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[1].id).isEqualTo("anotherqueueid")
    }

    @Test
    fun `should register multiple health indicators`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
      verify(beanFactory).registerSingleton(eq("anotherqueueid-health"), any<HmppsQueueHealth>())
    }
  }

  @Nested
  inner class `Create AWS HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsQueueProperties = HmppsQueueProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQSAsync>()
    private val sqsDlqClient = mock<AmazonSQSAsync>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsAmazonSQSAsync(any(), any(), any()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl("some dlq name")).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsQueueProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory).awsAmazonSQSAsync("dlq access key id", "dlq secret access key", "eu-west-2")
      verify(sqsFactory).awsAmazonSQSAsync("some access key id", "some secret access key", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(AmazonSQSAsync::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isInstanceOf(AmazonSQSAsync::class.java)
    }

    @Test
    fun `should return queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should register a health indicator`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueJmsListenerContainerFactory>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsQueueProperties = HmppsQueueProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQSAsync>()
    private val sqsDlqClient = mock<AmazonSQSAsync>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localStackAmazonSQSAsync(any(), any()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsDlqClient.getQueueUrl("some dlq name")).thenReturn(GetQueueUrlResult().withQueueUrl("some dlq url"))
      whenever(sqsDlqClient.getQueueAttributes(anyString(), anyList())).thenReturn(GetQueueAttributesResult().withAttributes(mapOf("QueueArn" to "some dlq arn")))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsQueueProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory, times(2)).localStackAmazonSQSAsync("http://localhost:4566", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(AmazonSQSAsync::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isInstanceOf(AmazonSQSAsync::class.java)
    }

    @Test
    fun `should return queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should register all beans created`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueJmsListenerContainerFactory>())
    }
  }
}
