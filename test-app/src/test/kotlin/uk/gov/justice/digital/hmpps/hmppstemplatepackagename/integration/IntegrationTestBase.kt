package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.core.dependencies.google.gson.Gson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.testcontainers.LocalStackContainer
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.testcontainers.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.InboundMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundEventsEmitter
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundMessageService
import uk.gov.justice.hmpps.sqs.HmppsAsyncQueueService
import uk.gov.justice.hmpps.sqs.HmppsQueueFactory
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.MissingQueueException
import uk.gov.justice.hmpps.sqs.MissingTopicException

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(IntegrationTestBase.SqsConfig::class, JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @BeforeEach
  fun `clear queues`() {
    inboundSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(inboundQueueUrl).build())
    inboundSqsDlqClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(inboundDlqUrl).build())
    outboundSqsClientSpy.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundQueueUrl).build())
    outboundSqsDlqClientSpy.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundDlqUrl).build())
    outboundTestSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundTestQueueUrl).build())
    outboundTestNoDlqSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundTestNoDlqQueueUrl).build())
    asyncSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(asyncQueueUrl).build()).get()
    asyncSqsDlqClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(asyncDlqUrl).build()).get()
  }

  fun HmppsSqsProperties.inboundQueueConfig() =
    queues["inboundqueue"] ?: throw MissingQueueException("inboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundQueueConfig() =
    queues["outboundqueue"] ?: throw MissingQueueException("outboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.asyncQueueConfig() =
    queues["asyncqueue"] ?: throw MissingQueueException("asyncqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.inboundTopicConfig() =
    topics["inboundtopic"] ?: throw MissingTopicException("inboundtopic has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundTopicConfig() =
    topics["outboundtopic"] ?: throw MissingTopicException("outboundtopic has not been loaded from configuration properties")

  private val inboundQueue by lazy { hmppsQueueService.findByQueueId("inboundqueue") ?: throw MissingQueueException("HmppsQueue inboundqueue not found") }
  private val outboundQueue by lazy { hmppsQueueService.findByQueueId("outboundqueue") ?: throw MissingQueueException("HmppsQueue outboundqueue not found") }
  private val outboundTestQueue by lazy { hmppsQueueService.findByQueueId("outboundtestqueue") ?: throw MissingQueueException("HmppsQueue outboundtestqueue not found") }
  private val inboundTopic by lazy { hmppsQueueService.findByTopicId("inboundtopic") ?: throw MissingQueueException("HmppsTopic inboundtopic not found") }
  private val outboundTestNoDlqQueue by lazy { hmppsQueueService.findByQueueId("outboundtestnodlqqueue") ?: throw MissingQueueException("HmppsQueue outboundtestnodlqqueue not found") }
  private val asyncQueue by lazy { hmppsAsyncQueueService.findByQueueId("asyncqueue") ?: throw MissingQueueException("HmppsQueue asyncqueue not found") }

  protected val inboundSqsClient by lazy { inboundQueue.sqsClient }
  protected val inboundSqsDlqClient by lazy { inboundQueue.sqsDlqClient as SqsClient }
  protected val inboundSnsClient by lazy { inboundTopic.snsClient }
  protected val outboundTestSqsClient by lazy { outboundTestQueue.sqsClient }
  protected val outboundTestNoDlqSqsClient by lazy { outboundTestNoDlqQueue.sqsClient }
  protected val asyncSqsClient by lazy { asyncQueue.sqsAsyncClient }
  protected val asyncSqsDlqClient by lazy { asyncQueue.sqsAsyncDlqClient as SqsAsyncClient }

  @SpyBean
  @Qualifier("outboundqueue-sqs-client")
  protected lateinit var outboundSqsClientSpy: SqsClient

  @SpyBean
  @Qualifier("outboundqueue-sqs-dlq-client")
  protected lateinit var outboundSqsDlqClientSpy: SqsClient

  protected val inboundQueueUrl by lazy { inboundQueue.queueUrl }
  protected val inboundDlqUrl by lazy { inboundQueue.dlqUrl as String }
  protected val outboundQueueUrl by lazy { outboundQueue.queueUrl }
  protected val outboundDlqUrl by lazy { outboundQueue.dlqUrl as String }
  protected val outboundTestQueueUrl by lazy { outboundTestQueue.queueUrl }
  protected val outboundTestNoDlqQueueUrl by lazy { outboundTestNoDlqQueue.queueUrl }
  protected val asyncQueueUrl by lazy { asyncQueue.queueUrl }
  protected val asyncDlqUrl by lazy { asyncQueue.dlqUrl as String }

  protected val inboundTopicArn by lazy { inboundTopic.arn }

  @Autowired
  protected lateinit var objectMapper: ObjectMapper

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var hmppsQueueService: HmppsQueueService

  @Autowired
  protected lateinit var hmppsAsyncQueueService: HmppsAsyncQueueService

  @SpyBean
  protected lateinit var inboundMessageServiceSpy: InboundMessageService

  @SpyBean
  protected lateinit var outboundMessageServiceSpy: OutboundMessageService

  @SpyBean
  protected lateinit var hmppsSqsPropertiesSpy: HmppsSqsProperties

  @SpyBean
  protected lateinit var outboundEventsEmitterSpy: OutboundEventsEmitter

  @Autowired
  lateinit var webTestClient: WebTestClient

  internal fun SqsClient.countMessagesOnQueue(queueUrl: String): Int =
    this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
      .let { it.attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0 }

  internal fun SqsAsyncClient.countMessagesOnQueue(queueUrl: String): Int =
    this.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).build())
      .let { it.get().attributes()[QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES]?.toInt() ?: 0 }

  internal fun HttpHeaders.authToken(roles: List<String> = listOf("ROLE_QUEUE_ADMIN")) {
    this.setBearerAuth(
      jwtAuthHelper.createJwt(
        subject = "SOME_USER",
        roles = roles,
        clientId = "some-client"
      )
    )
  }

  protected fun gsonString(any: Any) = Gson().toJson(any) as String

  @TestConfiguration
  class SqsConfig(private val hmppsQueueFactory: HmppsQueueFactory) {

    @Bean("outboundqueue-sqs-client")
    fun outboundQueueSqsClient(
      hmppsSqsProperties: HmppsSqsProperties,
      @Qualifier("outboundqueue-sqs-dlq-client") outboundQueueSqsDlqClient: SqsClient
    ): SqsClient =
      with(hmppsSqsProperties) {
        val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
        hmppsQueueFactory.createSqsClient(config, hmppsSqsProperties, outboundQueueSqsDlqClient)
      }

    @Bean("outboundqueue-sqs-dlq-client")
    fun outboundQueueSqsDlqClient(hmppsSqsProperties: HmppsSqsProperties): SqsClient =
      with(hmppsSqsProperties) {
        val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
        hmppsQueueFactory.createSqsDlqClient(config, hmppsSqsProperties)
      }
  }

  companion object {
    private val localStackContainer = LocalStackContainer.instance

    @JvmStatic
    @DynamicPropertySource
    fun testcontainers(registry: DynamicPropertyRegistry) {
      localStackContainer?.also { setLocalStackProperties(it, registry) }
    }
  }
}
