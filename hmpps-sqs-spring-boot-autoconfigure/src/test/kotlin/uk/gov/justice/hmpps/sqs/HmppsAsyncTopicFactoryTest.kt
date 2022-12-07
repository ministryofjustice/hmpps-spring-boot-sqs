package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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

@Suppress("ClassName")
class HmppsAsyncTopicFactoryTest {

  private val localstackArnPrefix = "arn:aws:sns:eu-west-2:000000000000:"

  private val context = mock<ConfigurableApplicationContext>()
  private val beanFactory = mock<ConfigurableListableBeanFactory>()
  private val snsAsyncFactory = mock<SnsAsyncClientFactory>()
  private val hmppsAsyncTopicFactory = HmppsAsyncTopicFactory(context, snsAsyncFactory)

  init {
    whenever(context.beanFactory).thenReturn(beanFactory)
  }

  @Nested
  inner class `Create async AWS HmppsTopics` {
    private val someTopicConfig = TopicConfig(arn = "some arn", accessKeyId = "some access key id", secretAccessKey = "some secret access key", asyncClient = true)
    private val hmppsSqsProperties = HmppsSqsProperties(queues = mock(), topics = mapOf("sometopicid" to someTopicConfig))
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsAsyncTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsAsyncFactory.awsSnsAsyncClient(anyString(), anyString(), anyString()))
        .thenReturn(snsClient)

      hmppsTopics = hmppsAsyncTopicFactory.createHmppsAsyncTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsAsyncFactory).awsSnsAsyncClient("some access key id", "some secret access key", "eu-west-2")
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }

  @Nested
  inner class `Create async LocalStack HmppsTopics` {
    private val someTopicConfig = TopicConfig(arn = "${localstackArnPrefix}some arn", accessKeyId = "some access key id", secretAccessKey = "some secret access key", asyncClient = true)
    private val hmppsSqsProperties = HmppsSqsProperties(provider = "localstack", queues = mock(), topics = mapOf("sometopicid" to someTopicConfig))
    private val snsClient = mock<SnsAsyncClient>()
    private lateinit var hmppsTopics: List<HmppsAsyncTopic>

    @BeforeEach
    fun `configure mocks and register queues`() {
      whenever(snsAsyncFactory.localstackSnsAsyncClient(anyString(), anyString()))
        .thenReturn(snsClient)
      whenever(snsClient.createTopic(any<CreateTopicRequest>()))
        .thenReturn(CompletableFuture.completedFuture(CreateTopicResponse.builder().build()))

      hmppsTopics = hmppsAsyncTopicFactory.createHmppsAsyncTopics(hmppsSqsProperties)
    }

    @Test
    fun `should create async aws sns clients`() {
      verify(snsAsyncFactory).localstackSnsAsyncClient("http://localhost:4566", "eu-west-2")
    }

    @Test
    fun `should return the topic details`() {
      assertThat(hmppsTopics[0].id).isEqualTo("sometopicid")
    }
  }
}