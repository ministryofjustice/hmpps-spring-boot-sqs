package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import java.util.concurrent.CompletableFuture

class HmppsQueueFactoryTest {

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<SqsClientFactory>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, sqsFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create AWS HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsDlqClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some dlq name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some dlq url").build()))
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build()
          )
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2")
      verify(sqsFactory).awsSqsAsyncClient("dlq access key id", "dlq secret access key", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isInstanceOf(SqsAsyncClient::class.java)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build()
          )
        )
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some dlq url").build()))
      whenever(sqsDlqClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.QUEUE_ARN to "some dlq arn")).build()))
      whenever(sqsDlqClient.createQueue(any<CreateQueueRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateQueueResponse.builder().build()))
      whenever(sqsClient.createQueue(any<CreateQueueRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateQueueResponse.builder().build()))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory, times(2)).localstackSqsAsyncClient("http://localhost:4566", "eu-west-2")
    }

    @Test
    fun `should return async clients`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isInstanceOf(SqsAsyncClient::class.java)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }
}
