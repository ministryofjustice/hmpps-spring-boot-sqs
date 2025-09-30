@file:Suppress("ClassName")

package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig
import java.util.concurrent.CompletableFuture

class HmppsQueueFactoryTest {

  private val context = mock<ConfigurableApplicationContext>()
  private val healthContributorRegistry = mock<HmppsHealthContributorRegistry>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<SqsClientFactory>()
  private val objectMapper = mock<ObjectMapper>()
  private val errorVisibilityHandler = mock<HmppsErrorVisibilityHandler>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, healthContributorRegistry, sqsFactory, errorVisibilityHandler, objectMapper)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create AWS HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), useWebToken = false)
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
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
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2", false, true)
      verify(sqsFactory).awsSqsAsyncClient("dlq access key id", "dlq secret access key", "eu-west-2", false, true)
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
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }

    @Test
    fun `should not subscribe to topics`() {
      val someQueueConfig = QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", subscribeTopicId = "sometopicid")
      val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to TopicConfig("arn:aws:sns:1:2:3", "any", "any")), useWebToken = false)
      val snsClient = mock<SnsAsyncClient>()
      val topics = listOf(HmppsTopic("sometopicid", "arn:aws:sns:1:2:3", snsClient))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)

      verify(snsClient, never()).subscribe(any<SubscribeRequest>())
    }
  }

  @Nested
  inner class `Create AWS HmppsQueue with Web Token Identity based SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsSqsProperties = HmppsSqsProperties(useWebToken = true, queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
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
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async clients from sqs factory`() {
      verify(sqsFactory, times(2)).awsSqsAsyncClient("", "", "eu-west-2", true, true)
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
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }

    @Test
    fun `should not subscribe to topics`() {
      val someQueueConfig = QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", subscribeTopicId = "sometopicid")
      val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to TopicConfig("arn:aws:sns:1:2:3", "any", "any")))
      val snsClient = mock<SnsAsyncClient>()
      val topics = listOf(HmppsTopic("sometopicid", "arn:aws:sns:1:2:3", snsClient))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)

      verify(snsClient, never()).subscribe(any<SubscribeRequest>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", dlqName = "some-queue-name-dlq")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
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
      verify(sqsFactory, times(2)).localstackSqsAsyncClient("http://localhost:4566", "eu-west-2", true)
    }

    @Test
    fun `should create a dead letter queue`() {
      verify(sqsDlqClient).createQueue(
        CreateQueueRequest.builder()
          .queueName("some-queue-name-dlq")
          .attributes(mapOf())
          .build(),
      )
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
      assertThat(hmppsQueues[0].dlqName).isEqualTo("some-queue-name-dlq")
    }

    @Test
    fun `should register all beans created`() {
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-dlq-client"), any<SqsAsyncClient>())
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }

    @Test
    fun `should subscribe to topics`() {
      val someQueueConfig = QueueConfig(queueName = "any", queueAccessKeyId = "any", queueSecretAccessKey = "any", subscribeTopicId = "sometopicid")
      val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to TopicConfig("arn:aws:sns:1:2:3", "any", "any")))
      val snsClient = mock<SnsAsyncClient>()
      val topics = listOf(HmppsTopic("sometopicid", "arn:aws:sns:1:2:3", snsClient))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)

      verify(snsClient).subscribe(
        check<SubscribeRequest> {
          assertThat(it.topicArn()).isEqualTo("arn:aws:sns:1:2:3")
        },
      )
    }
  }

  @Nested
  inner class `Create LocalStack FIFO HmppsQueue` {
    private val someQueueConfig = QueueConfig(queueName = "some-queue-name.fifo", dlqName = "some-queue-name-dlq.fifo")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val sqsDlqClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
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
    fun `should create a FIFO queue`() {
      verify(sqsClient).createQueue(
        CreateQueueRequest.builder()
          .queueName("some-queue-name.fifo")
          .attributes(mapOf(QueueAttributeName.REDRIVE_POLICY to """{"deadLetterTargetArn":"some dlq arn","maxReceiveCount":"5"}""", QueueAttributeName.VISIBILITY_TIMEOUT to "30", QueueAttributeName.FIFO_QUEUE to "true"))
          .build(),
      )
    }

    @Test
    fun `should create a FIFO dead letter queue`() {
      verify(sqsDlqClient).createQueue(
        CreateQueueRequest.builder()
          .queueName("some-queue-name-dlq.fifo")
          .attributes(mapOf(QueueAttributeName.FIFO_QUEUE to "true"))
          .build(),
      )
    }
  }
}
