@file:Suppress("ClassName")

package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
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

class HmppsQueueFactoryTest_NoDlq {

  private val localstackArnPrefix = "arn:aws:sns:eu-west-2:000000000000:"

  private val context = mock<ConfigurableApplicationContext>()
  private val healthContributorRegistry = mock<HmppsHealthContributorRegistry>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<SqsClientFactory>()
  private val objectMapper = mock<ObjectMapper>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, healthContributorRegistry, sqsFactory, objectMapper)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create single AWS HmppsQueue with no dlq` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), useWebToken = false)
    private val sqsClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
        CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()),
      )
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
    fun `creates aws sqs client but does not create aws sqs dlq client from sqs factory`() {
      verify(sqsFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2", false, true)
      verifyNoMoreInteractions(sqsFactory)
    }

    @Test
    fun `should return the queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
    }

    @Test
    fun `should return the queue SqsAsyncClient client`() {
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
    fun `should not return dlq client`() {
      assertThat(hmppsQueues[0].sqsDlqClient).isNull()
    }

    @Test
    fun `should not return dlq name`() {
      assertThat(hmppsQueues[0].dlqName).isNull()
    }

    @Test
    fun `should not return dlq url`() {
      assertThat(hmppsQueues[0].dlqUrl).isNull()
    }

    @Test
    fun `should register a health indicator`() {
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
    }

    @Test
    fun `should register the sqs client but not the dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<SqsAsyncClient>())
    }

    @Test
    fun `should register the sqs listener factory`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }

  @Nested
  inner class `Create single LocalStack HmppsQueue with no dlq` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() = runBlocking {
      whenever(sqsClient.createQueue(any<CreateQueueRequest>())).thenReturn(
        CompletableFuture.completedFuture(CreateQueueResponse.builder().build()),
      )
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
        CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()),
      )
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
    fun `creates LocalStack sqs client from sqs factory but not dlq client`() {
      verify(sqsFactory).localstackSqsAsyncClient(localstackUrl = "http://localhost:4566", region = "eu-west-2", true)
      verifyNoMoreInteractions(sqsFactory)
    }

    @Test
    fun `should return the queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
    }

    @Test
    fun `should return the queue SqsAsyncClient client`() {
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
    fun `should not return a dlq client`() {
      assertThat(hmppsQueues[0].sqsDlqClient).isNull()
    }

    @Test
    fun `should not return a dlq name`() {
      assertThat(hmppsQueues[0].dlqName).isNull()
    }

    @Test
    fun `should not return a dlq url`() {
      assertThat(hmppsQueues[0].dlqUrl).isNull()
    }

    @Test
    fun `should register a health indicator`() {
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
    }

    @Test
    fun `should register the sqs client but not the dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<SqsAsyncClient>())
    }

    @Test
    fun `should create a queue without a redrive policy`() {
      verify(sqsClient).createQueue(
        check<CreateQueueRequest> {
          assertThat(it.attributes()).doesNotContainEntry(QueueAttributeName.REDRIVE_POLICY, """{"deadLetterTargetArn":"some dlq arn","maxReceiveCount":"5"}""")
        },
      )
    }
  }

  @Nested
  inner class `Create multiple AWS HmppsQueues without dlqs` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val anotherQueueConfig = QueueConfig(queueName = "another queue name", queueAccessKeyId = "another access key id", queueSecretAccessKey = "another secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig, "anotherqueueid" to anotherQueueConfig), useWebToken = false)
    private val sqsClient = mock<SqsAsyncClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("some queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()))
      whenever(sqsClient.getQueueUrl(GetQueueUrlRequest.builder().queueName("another queue name").build()))
        .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("another queue url").build()))
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
    fun `should create multiple sqs clients but no dlq clients from sqs factory`() {
      verify(sqsFactory).awsSqsAsyncClient("some access key id", "some secret access key", "eu-west-2", false, true)
      verify(sqsFactory).awsSqsAsyncClient("another access key id", "another secret access key", "eu-west-2", false, true)
      verifyNoMoreInteractions(sqsFactory)
    }

    @Test
    fun `should return multiple queue details`() {
      assertThat(hmppsQueues[0].id).isEqualTo("somequeueid")
      assertThat(hmppsQueues[1].id).isEqualTo("anotherqueueid")
    }

    @Test
    fun `should register multiple health indicators`() {
      verify(healthContributorRegistry).registerContributor(eq("somequeueid-health"), any())
      verify(healthContributorRegistry).registerContributor(eq("anotherqueueid-health"), any())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(queueName = "some-queue-name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter")
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some-topic-name", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val snsClient = mock<SnsAsyncClient>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsClient.createQueue(any<CreateQueueRequest>())).thenReturn(
        CompletableFuture.completedFuture(CreateQueueResponse.builder().build()),
      )
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
        CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()),
      )
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should create a queue`() {
      verify(sqsClient).createQueue(CreateQueueRequest.builder().queueName("some-queue-name").attributes(mapOf()).build())
    }

    @Test
    fun `should return the queue SqsAsyncClient client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should subscribe to the topic`() {
      verify(snsClient).subscribe(
        check<SubscribeRequest> { subscribeRequest ->
          assertThat(subscribeRequest.topicArn()).isEqualTo("some topic arn")
          assertThat(subscribeRequest.protocol()).isEqualTo("sqs")
          assertThat(subscribeRequest.endpoint()).isEqualTo("queue:arn")
          assertThat(subscribeRequest.attributes()["FilterPolicy"]).isEqualTo("some topic filter")
        },
      )
    }
  }

  @Nested
  inner class `Create LocalStack FIFO HmppsQueue with FIFO topic subscription` {
    private val someQueueConfig = QueueConfig(subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter", queueName = "some-queue-name.fifo", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", fifoQueue = true, fifoThroughputLimit = "perQueue")
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some-topic-name.fifo", accessKeyId = "topic access key", secretAccessKey = "topic secret", fifoTopic = true, contentBasedDeduplication = true)
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val snsClient = mock<SnsAsyncClient>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsClient.createQueue(any<CreateQueueRequest>())).thenReturn(
        CompletableFuture.completedFuture(CreateQueueResponse.builder().build()),
      )
      whenever(sqsFactory.localstackSqsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
        CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()),
      )
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should create a FIFO queue`() {
      verify(sqsClient).createQueue(CreateQueueRequest.builder().queueName("some-queue-name.fifo").attributes(mapOf(QueueAttributeName.FIFO_QUEUE to "true", QueueAttributeName.FIFO_THROUGHPUT_LIMIT to "perQueue")).build())
    }

    @Test
    fun `should subscribe to the topic`() {
      verify(snsClient).subscribe(
        check<SubscribeRequest> { subscribeRequest ->
          assertThat(subscribeRequest.topicArn()).isEqualTo("some topic arn")
          assertThat(subscribeRequest.protocol()).isEqualTo("sqs")
          assertThat(subscribeRequest.endpoint()).isEqualTo("queue:arn")
          assertThat(subscribeRequest.attributes()["FilterPolicy"]).isEqualTo("some topic filter")
        },
      )
    }
  }

  @Nested
  inner class `Create AWS HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter")
    private val someTopicConfig = TopicConfig(arn = "some topic arn", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<SqsAsyncClient>()
    private val snsClient = mock<SnsAsyncClient>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(
        CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl("some queue url").build()),
      )
      whenever(sqsClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(
          CompletableFuture.completedFuture(
            GetQueueAttributesResponse.builder()
              .attributes(mutableMapOf(QueueAttributeName.QUEUE_ARN to "queue:arn")).build(),
          ),
        )

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should return the queue SqsAsyncClient client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should not subscribe to the topic`() {
      verifyNoMoreInteractions(snsClient)
    }
  }
}
