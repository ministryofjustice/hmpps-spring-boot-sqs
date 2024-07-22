@file:Suppress("ClassName")

package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import software.amazon.awssdk.services.sns.model.CreateTopicResponse
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig
import java.util.concurrent.CompletableFuture

class HmppsTopicFactoryTest {

  private val localstackArnPrefix = "arn:aws:sns:eu-west-2:000000000000:"

  private val context = mock<ConfigurableApplicationContext>()
  private val healthContributorRegistry = mock<HmppsHealthContributorRegistry>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val snsClientFactory = mock<SnsClientFactory>()
  private val hmppsTopicFactory = HmppsTopicFactory(context, healthContributorRegistry, snsClientFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create AWS HmppsTopics` {
    private val someTopicConfig = TopicConfig(arn = "some arn", accessKeyId = "some access key id", secretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mock(), topics = mapOf("sometopicid" to someTopicConfig), useWebToken = false)
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsClientFactory.awsSnsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(snsClient)

      hmppsTopics = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsClientFactory).awsSnsAsyncClient("some access key id", "some secret access key", "eu-west-2", false, false)
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }

  @Nested
  inner class `Create AWS HmppsTopics using Web Token Identity` {
    private val someTopicConfig = TopicConfig(arn = "some arn")
    private val hmppsSqsProperties = HmppsSqsProperties(useWebToken = true, queues = mock(), topics = mapOf("sometopicid" to someTopicConfig))
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsClientFactory.awsSnsAsyncClient(anyString(), anyString(), anyString(), anyBoolean(), anyBoolean()))
        .thenReturn(snsClient)

      hmppsTopics = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsClientFactory).awsSnsAsyncClient("", "", "eu-west-2", true, false)
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }

  @Nested
  inner class `Create async LocalStack HmppsTopics` {
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some arn", accessKeyId = "some access key id", secretAccessKey = "some secret access key")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mock(), topics = mapOf("sometopicid" to someTopicConfig))
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsClientFactory.localstackSnsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(snsClient)
      whenever(snsClient.createTopic(any<CreateTopicRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateTopicResponse.builder().build()))

      hmppsTopics = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsClientFactory).localstackSnsAsyncClient("http://localhost:4566", "eu-west-2", false)
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }

  @Nested
  inner class `Create FIFO LocalStack HmppsTopics` {
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some arn", accessKeyId = "some access key id", secretAccessKey = "some secret access key", fifoTopic = "true", contentBasedDeduplication = "true")
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mock(), topics = mapOf("sometopicid" to someTopicConfig))
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsClientFactory.localstackSnsAsyncClient(anyString(), anyString(), anyBoolean()))
        .thenReturn(snsClient)
      whenever(snsClient.createTopic(any<CreateTopicRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateTopicResponse.builder().build()))

      hmppsTopics = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsClientFactory).localstackSnsAsyncClient("http://localhost:4566", "eu-west-2", false)
    }

    @Test
    fun `should create a FIFO topic`() {
      verify(snsClient).createTopic(
        CreateTopicRequest.builder()
          .name(someTopicConfig.name)
          .attributes(mapOf("fifo_topic" to someTopicConfig.fifoTopic, "content_based_deduplication" to someTopicConfig.contentBasedDeduplication))
          .build(),
      )
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }
}
