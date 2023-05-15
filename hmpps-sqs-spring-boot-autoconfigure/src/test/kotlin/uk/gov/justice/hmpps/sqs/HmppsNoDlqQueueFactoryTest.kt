package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSAsync
import com.amazonaws.services.sqs.model.CreateQueueRequest
import com.amazonaws.services.sqs.model.GetQueueUrlResult
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
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.QueueConfig
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig

class HmppsNoDlqQueueFactoryTest {

  private val localstackArnPrefix = "arn:aws:sns:eu-west-2:000000000000:"

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val sqsFactory = mock<AmazonSqsFactory>()
  private val hmppsQueueFactory = HmppsQueueFactory(context, sqsFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create single AWS HmppsQueue with no dlq` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `creates aws sqs client but does not create aws sqs dlq client from sqs factory`() {
      verify(sqsFactory).awsSqsClient("somequeueid", "some queue name", "some access key id", "some secret access key", "eu-west-2", false, false)
      verifyNoMoreInteractions(sqsFactory)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
    }

    @Test
    fun `should register the sqs client but not the dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<AmazonSQS>())
    }

    @Test
    fun `should register the jms listener factory`() {
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
    }
  }

  @Nested
  inner class `Create single LocalStack HmppsQueue with no dlq` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localStackSqsClient(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `creates LocalStack sqs client from sqs factory but not dlq client`() {
      verify(sqsFactory).localStackSqsClient(queueId = "somequeueid", localstackUrl = "http://localhost:4566", region = "eu-west-2", queueName = "some queue name", asyncClient = false)
      verifyNoMoreInteractions(sqsFactory)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-health"), any<HmppsQueueHealth>())
    }

    @Test
    fun `should register the sqs client but not the dlq client`() {
      verify(beanFactory).registerSingleton("somequeueid-sqs-client", sqsClient)
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<AmazonSQS>())
    }

    @Test
    fun `should create a queue without a redrive policy`() {
      verify(sqsClient).createQueue(
        check<CreateQueueRequest> {
          assertThat(it.attributes).doesNotContainEntry("RedrivePolicy", """{"deadLetterTargetArn":"some dlq arn","maxReceiveCount":"5"}""")
        }
      )
    }
  }

  @Nested
  inner class `Create multiple AWS HmppsQueues without dlqs` {
    private val someQueueConfig = QueueConfig(queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val anotherQueueConfig = QueueConfig(queueName = "another queue name", queueAccessKeyId = "another access key id", queueSecretAccessKey = "another secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig, "anotherqueueid" to anotherQueueConfig))
    private val sqsClient = mock<AmazonSQS>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))
      whenever(sqsClient.getQueueUrl("another queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("another queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create multiple sqs clients but no dlq clients from sqs factory`() {
      verify(sqsFactory).awsSqsClient("somequeueid", "some queue name", "some access key id", "some secret access key", "eu-west-2", false, false)
      verify(sqsFactory).awsSqsClient("anotherqueueid", "another queue name", "another access key id", "another secret access key", "eu-west-2", false, false)
      verifyNoMoreInteractions(sqsFactory)
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
  inner class `Create AWS HmppsQueue with asynchronous SQS client` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQSAsync>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async client but no dlq client from sqs factory`() {
      verify(sqsFactory).awsSqsClient("somequeueid", "some queue name", "some access key id", "some secret access key", "eu-west-2", true, false)
      verifyNoMoreInteractions(sqsFactory)
    }

    @Test
    fun `should return async client but no dlq client`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(AmazonSQSAsync::class.java)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
      verify(beanFactory, never()).registerSingleton(contains("dlq-client"), any<AmazonSQS>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with asynchronous SQS clients` {
    private val someQueueConfig = QueueConfig(asyncQueueClient = true, asyncDlqClient = true, queueName = "some queue name")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig))
    private val sqsClient = mock<AmazonSQSAsync>()
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localStackSqsClient(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl("some queue name")).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties)
    }

    @Test
    fun `should create async client but no dlq from sqs factory`() {
      verify(sqsFactory).localStackSqsClient("somequeueid", "some queue name", "http://localhost:4566", "eu-west-2", true)
      verify(sqsFactory, never()).localStackSqsDlqClient(anyString(), anyString(), anyString(), anyString(), anyBoolean())
    }

    @Test
    fun `should return async client but no dlq client`() {
      assertThat(hmppsQueues[0].sqsClient).isInstanceOf(AmazonSQSAsync::class.java)
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
      verify(beanFactory).registerSingleton(eq("somequeueid-sqs-client"), any<AmazonSQS>())
      verify(beanFactory).registerSingleton(eq("somequeueid-jms-listener-factory"), any<HmppsQueueDestinationContainerFactory>())
      verify(beanFactory, never()).registerSingleton(contains("sqs-dlq-client"), any<AmazonSQS>())
    }
  }

  @Nested
  inner class `Create LocalStack HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter", queueName = "some-queue-name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some-topic-name", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<AmazonSQS>()
    private val snsClient = mock<AmazonSNS>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.localStackSqsClient(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

      hmppsQueues = hmppsQueueFactory.createHmppsQueues(hmppsSqsProperties, topics)
    }

    @Test
    fun `should return the queue AmazonSQS client`() {
      assertThat(hmppsQueues[0].sqsClient).isEqualTo(sqsClient)
    }

    @Test
    fun `should subscribe to the topic`() {
      verify(snsClient).subscribe(
        check { subscribeRequest ->
          assertThat(subscribeRequest.topicArn).isEqualTo("some topic arn")
          assertThat(subscribeRequest.protocol).isEqualTo("sqs")
          assertThat(subscribeRequest.endpoint).isEqualTo("some queue url")
          assertThat(subscribeRequest.attributes["FilterPolicy"]).isEqualTo("some topic filter")
        }
      )
    }
  }

  @Nested
  inner class `Create AWS HmppsQueue with topic subscription` {
    private val someQueueConfig = QueueConfig(subscribeTopicId = "sometopicid", subscribeFilter = "some topic filter", queueName = "some queue name", queueAccessKeyId = "some access key id", queueSecretAccessKey = "some secret access key")
    private val someTopicConfig = TopicConfig(arn = "some topic arn", accessKeyId = "topic access key", secretAccessKey = "topic secret")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mapOf("somequeueid" to someQueueConfig), topics = mapOf("sometopicid" to someTopicConfig))
    private val sqsClient = mock<AmazonSQS>()
    private val snsClient = mock<AmazonSNS>()
    private val topics = listOf(HmppsTopic(id = "sometopicid", arn = "some topic arn", snsClient = snsClient))
    private lateinit var hmppsQueues: List<HmppsQueue>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(sqsFactory.awsSqsClient(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(sqsClient)
      whenever(sqsClient.getQueueUrl(anyString())).thenReturn(GetQueueUrlResult().withQueueUrl("some queue url"))

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
