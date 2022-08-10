package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import java.util.concurrent.CompletableFuture

class HmppsAsyncQueueFactoryTest {

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<SqsAsyncClientFactory>()
  private val hmppsQueueFactory = HmppsAsyncQueueFactory(context, sqsFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create AWS HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsAsyncQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some dlq name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some dlq url").build()))

      hmppsQueues = hmppsQueueFactory.createHmppsAsyncQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2")
      verify(sqsFactory).awsSqsAsyncClient("dlq access key id", "dlq secret access key", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsAsyncClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsAsyncDlqClient).isInstanceOf(SqsAsyncClient::class.java)
    }

    @Test
    fun `should return queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should register a health indicator`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsAsyncQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory, never()).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsAsyncQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some dlq name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some dlq url").build()))
      whenever(sqsDlqClient.getQueueAttributes(GetQueueAttributesRequest.builder().attributeNames(QueueAttributeName.QUEUE_ARN).build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.QUEUE_ARN to "some dlq arn")).build()))

      hmppsQueues = hmppsQueueFactory.createHmppsAsyncQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory, times(2)).localstackSqsAsyncClient("http://localhost:4566", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsAsyncClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsAsyncDlqClient).isInstanceOf(SqsAsyncClient::class.java)
    }

    @Test
    fun `should return queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some dlq name")
    }

    @Test
    fun `should register all beans created`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsAsyncQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory, never()).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }
}
