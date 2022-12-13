package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import java.util.concurrent.CompletableFuture

class HmppsNoDlqQueueFactoryTest {

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsAsyncFactory = mock<SqsClientFactory>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, sqsAsyncFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create AWS HmppsQueue with asynchronous SQS client` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsAsyncFactory.awsSqsAsyncClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async client but no dlq client from sqs factory`() {
      verify(sqsAsyncFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2")
      verifyNoMoreInteractions(sqsAsyncFactory)
    }

    @Test
    fun `should return async client but no dlq client`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isNull()
    }

    @Test
    fun `should return queue details but no dlq details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isNull()
    }

    @Test
    fun `should register a health indicator but not for dlq`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(anyString(), any<HmppsQueueDestinationContainerFactory>())
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<SqsAsyncClient>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsAsyncClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsAsyncFactory.localstackSqsAsyncClient(anyString(), anyString()))
        .thenReturn(sqsAsyncClient)
      whenever(sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsAsyncClient.createQueue(any<CreateQueueRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateQueueResponse.builder().build()))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async client but no dlq from sqs factory`() {
      verify(sqsAsyncFactory).localstackSqsAsyncClient("http://localhost:4566", "eu-west-2")
      verifyNoMoreInteractions(sqsAsyncFactory)
    }

    @Test
    fun `should return async client but no dlq client`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(SqsAsyncClient::class.java)
      assertThat(hmppsQueues[0].sqsDlqClient).isNull()
    }

    @Test
    fun `should return queue details but no dlq details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[0].queueName).isEqualTo("some queue name")
      assertThat(hmppsQueues[0].dlqName).isNull()
    }

    @Test
    fun `should register all beans created`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
      verify(beanFactory, never()).registerSingleton(anyString(), any<SqsClient>())
    }
  }
}
