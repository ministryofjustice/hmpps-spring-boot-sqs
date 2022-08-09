package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.SubscribeRequest
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig

class HmppsQueueFactoryTest {

  private val localstackArnPrefix = "arn:aws:sns:eu-west-2:000000000000:"

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<SqsClientFactory>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, sqsFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create single AWS HmppsQueue` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `creates aws sqs dlq client from sqs factory`() {
      verify(sqsFactory).awsSqsClient("dlq access key id", "dlq secret access key", "eu-west-2")
    }

    @Test
    fun `creates aws sqs client from sqs factory`() {
      verify(sqsFactory).awsSqsClient("some access key id", "some secret access key", "eu-west-2")
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
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }

  @Nested
  inner class `Create single LocalStack HmppsQueue` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", dlqName = "some dlq name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsClient(anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(sqsDlqClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.QUEUE_ARN to "some dlq arn")).build())

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `creates LocalStack sqs clients from sqs factory`() {
      verify(sqsFactory, times(2)).localstackSqsClient(localstackUrl = "http://localhost:4566", region = "eu-west-2")
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
      verify(sqsDlqClient).getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl("some dlq url").attributeNames(QueueAttributeName.QUEUE_ARN).build())
    }

    @Test
    fun `should create a queue with a redrive policy`() {
      verify(sqsClient).createQueue(
        check<CreateQueueRequest> {
          assertThat(it.attributes()).containsEntry(QueueAttributeName.REDRIVE_POLICY, """{"deadLetterTargetArn":"some dlq arn","maxReceiveCount":"5"}""")
        }
      )
    }
  }

  @Nested
  inner class `Create multiple AWS HmppsQueues` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val anotherQueueConfig = QueueConfig(queueName = "another queue name", queueAccessKeyId = "another access key id", queueSecretAccessKey = "another secret access key", dlqName = "another dlq name", dlqAccessKeyId = "another dlq access key id", dlqSecretAccessKey = "another dlq secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig, "anotherqueueid" to anotherQueueConfig))
    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
        .thenReturn(GetQueueUrlResponse.builder().queueUrl("another queue url").build())
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>()))
        .thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
        .thenReturn(GetQueueUrlResponse.builder().queueUrl("another dlq url").build())

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create multiple dlq clients from sqs factory`() {
      verify(sqsFactory).awsSqsClient("dlq access key id", "dlq secret access key", "eu-west-2")
      verify(sqsFactory).awsSqsClient("another dlq access key id", "another dlq secret access key", "eu-west-2")
    }

    @Test
    fun `should create multiple sqs clients from sqs factory`() {
      verify(sqsFactory).awsSqsClient("some access key id", "some secret access key", "eu-west-2")
      verify(sqsFactory).awsSqsClient("another access key id", "another secret access key", "eu-west-2")
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
  inner class `Create LocalStack HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter", queueName = "some-queue-name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some-topic-name", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()
    private val snsClient = mock<SnsClient>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localstackSqsClient(anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(sqsDlqClient.getQueueAttributes(any<GetQueueAttributesRequest>()))
        .thenReturn(GetQueueAttributesResponse.builder().attributes(mapOf(QueueAttributeName.QUEUE_ARN to "some dlq arn")).build())

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should return the queue AmazonSQS client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should subscribe to the topic`() {
      verify(snsClient).subscribe(
        check<SubscribeRequest> { subscribeRequest ->
          assertThat(subscribeRequest.topicArn()).isEqualTo("some topic arn")
          assertThat(subscribeRequest.protocol()).isEqualTo("sqs")
          assertThat(subscribeRequest.endpoint()).isEqualTo("http://localhost:4566/queue/some-queue-name")
          assertThat(subscribeRequest.attributes()["FilterPolicy"]).isEqualTo("some topic filter")
        }
      )
    }
  }

  @Nested
  inner class `Create AWS HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter", queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key", dlqName = "some dlq name", dlqAccessKeyId = "dlq access key id", dlqSecretAccessKey = "dlq secret access key")
    private val someTopicConfig = TopicConfig(arn = "some topic arn", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<SqsClient>()
    private val sqsDlqClient = mock<SqsClient>()
    private val snsClient = mock<SnsClient>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString()))
        .thenReturn(sqsDlqClient)
        .thenReturn(sqsClient)
      whenever(sqsDlqClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some dlq url").build())
      whenever(sqsClient.getQueueUrl(any<GetQueueUrlRequest>())).thenReturn(GetQueueUrlResponse.builder().queueUrl("some queue url").build())

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should return the queue AmazonSQS client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should not subscribe to the topic`() {
      verifyNoMoreInteractions(snsClient)
    }
  }
}
