package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import com.microsoft.applicationinsights.TelemetryClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.reactive.server.WebTestClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.testcontainers.LocalStackContainer
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.testcontainers.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.InboundMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundEventsEmitter
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundMessageService
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
    inboundSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(inboundQueueUrl).build()).get()
    inboundSqsDlqClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(inboundDlqUrl).build()).get()
    outboundSqsClientSpy.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundQueueUrl).build()).get()
    outboundSqsDlqClientSpy.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundDlqUrl).build()).get()
    outboundTestSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundTestQueueUrl).build()).get()
    outboundTestNoDlqSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundTestNoDlqQueueUrl).build()).get()
    fifoSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(fifoQueueUrl).build()).get()
    inboundSqsOnlyClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(inboundSqsOnlyQueueUrl).build()).get()
    outboundSqsOnlyClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundSqsOnlyQueueUrl).build()).get()
    outboundSqsOnlyTestSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(outboundSqsOnlyTestQueueUrl).build()).get()
    auditSqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(auditQueueUrl).build()).get()
  }

  fun HmppsSqsProperties.inboundQueueConfig() = queues["inboundqueue"] ?: throw MissingQueueException("inboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundQueueConfig() = queues["outboundqueue"] ?: throw MissingQueueException("outboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.auditQueueConfig() = queues["audit"] ?: throw MissingQueueException("audit has not been loaded from configuration properties")

  fun HmppsSqsProperties.inboundTopicConfig() = topics["inboundtopic"] ?: throw MissingTopicException("inboundtopic has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundTopicConfig() = topics["outboundtopic"] ?: throw MissingTopicException("outboundtopic has not been loaded from configuration properties")

  protected val inboundQueue by lazy { hmppsQueueService.findByQueueId("inboundqueue") ?: throw MissingQueueException("HmppsQueue inboundqueue not found") }
  protected val outboundQueue by lazy { hmppsQueueService.findByQueueId("outboundqueue") ?: throw MissingQueueException("HmppsQueue outboundqueue not found") }
  private val outboundTestQueue by lazy { hmppsQueueService.findByQueueId("outboundtestqueue") ?: throw MissingQueueException("HmppsQueue outboundtestqueue not found") }
  private val inboundTopic by lazy { hmppsQueueService.findByTopicId("inboundtopic") ?: throw MissingQueueException("HmppsTopic inboundtopic not found") }
  protected val outboundTopic by lazy { hmppsQueueService.findByTopicId("outboundtopic") ?: throw MissingQueueException("HmppsTopic outboundtopic not found") }
  internal val fifoTopic by lazy { hmppsQueueService.findByTopicId("fifotopic") ?: throw MissingQueueException("HmppsTopic fifotopic not found") }
  private val outboundTestNoDlqQueue by lazy { hmppsQueueService.findByQueueId("outboundtestnodlqqueue") ?: throw MissingQueueException("HmppsQueue outboundtestnodlqqueue not found") }
  private val auditQueue by lazy { hmppsQueueService.findByQueueId("audit") ?: throw MissingQueueException("HmppsQueue audit not found") }
  protected val fifoQueue by lazy { hmppsQueueService.findByQueueId("fifoqueue") ?: throw MissingQueueException("HmppsQueue fifoqueue not found") }
  private val inboundSqsOnlyQueue by lazy { hmppsQueueService.findByQueueId("inboundsqsonlyqueue") ?: throw MissingQueueException("HmppsQueue inboundsqsonlyqueue not found") }
  private val outboundSqsOnlyQueue by lazy { hmppsQueueService.findByQueueId("outboundsqsonlyqueue") ?: throw MissingQueueException("HmppsQueue outboundsqsonlyqueue not found") }
  protected val outboundSqsOnlyTestQueue by lazy { hmppsQueueService.findByQueueId("outboundsqsonlytestqueue") ?: throw MissingQueueException("HmppsQueue outboundsqsonlytestqueue not found") }

  protected val inboundSqsClient by lazy { inboundQueue.sqsClient }
  protected val inboundSqsDlqClient by lazy { inboundQueue.sqsDlqClient as SqsAsyncClient }
  protected val inboundSnsClient by lazy { inboundTopic.snsClient }
  protected val outboundTestSqsClient by lazy { outboundTestQueue.sqsClient }
  protected val outboundTestNoDlqSqsClient by lazy { outboundTestNoDlqQueue.sqsClient }
  protected val auditSqsClient by lazy { auditQueue.sqsClient }
  protected val fifoSqsClient by lazy { fifoQueue.sqsClient }
  protected val inboundSqsOnlyClient by lazy { inboundSqsOnlyQueue.sqsClient }
  protected val outboundSqsOnlyClient by lazy { outboundSqsOnlyQueue.sqsClient }
  protected val outboundSqsOnlyTestSqsClient by lazy { outboundSqsOnlyTestQueue.sqsClient }

  @MockitoSpyBean
  @Qualifier("outboundqueue-sqs-client")
  protected lateinit var outboundSqsClientSpy: SqsAsyncClient

  @MockitoSpyBean
  @Qualifier("outboundqueue-sqs-dlq-client")
  protected lateinit var outboundSqsDlqClientSpy: SqsAsyncClient

  protected val inboundQueueUrl by lazy { inboundQueue.queueUrl }
  protected val inboundDlqUrl by lazy { inboundQueue.dlqUrl as String }
  protected val outboundQueueUrl by lazy { outboundQueue.queueUrl }
  protected val outboundDlqUrl by lazy { outboundQueue.dlqUrl as String }
  protected val outboundTestQueueUrl by lazy { outboundTestQueue.queueUrl }
  protected val outboundTestNoDlqQueueUrl by lazy { outboundTestNoDlqQueue.queueUrl }
  protected val auditQueueUrl by lazy { auditQueue.queueUrl }
  protected val fifoQueueUrl by lazy { fifoQueue.queueUrl }
  protected val inboundSqsOnlyQueueUrl by lazy { inboundSqsOnlyQueue.queueUrl }
  protected val outboundSqsOnlyQueueUrl by lazy { outboundSqsOnlyQueue.queueUrl }
  protected val outboundSqsOnlyTestQueueUrl by lazy { outboundSqsOnlyTestQueue.queueUrl }

  protected val inboundTopicArn by lazy { inboundTopic.arn }

  @Autowired
  protected lateinit var objectMapper: ObjectMapper

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var hmppsQueueService: HmppsQueueService

  @MockitoSpyBean
  protected lateinit var inboundMessageServiceSpy: InboundMessageService

  @MockitoSpyBean
  protected lateinit var outboundMessageServiceSpy: OutboundMessageService

  @MockitoSpyBean
  protected lateinit var hmppsSqsPropertiesSpy: HmppsSqsProperties

  @MockitoSpyBean
  protected lateinit var outboundEventsEmitterSpy: OutboundEventsEmitter

  @MockitoSpyBean
  protected lateinit var telemetryClient: TelemetryClient

  @Autowired
  lateinit var webTestClient: WebTestClient

  internal fun HttpHeaders.authToken(roles: List<String> = listOf("ROLE_QUEUE_ADMIN")) {
    this.setBearerAuth(
      jwtAuthHelper.createJwt(
        subject = "SOME_USER",
        roles = roles,
        clientId = "some-client",
      ),
    )
  }

  protected fun gsonString(any: Any) = Gson().toJson(any) as String

  @TestConfiguration
  class SqsConfig(private val hmppsQueueFactory: HmppsQueueFactory) {

    @Bean("outboundqueue-sqs-client")
    fun outboundQueueSqsClient(
      hmppsSqsProperties: HmppsSqsProperties,
      @Qualifier("outboundqueue-sqs-dlq-client") outboundQueueSqsDlqClient: SqsAsyncClient,
    ): SqsAsyncClient = with(hmppsSqsProperties) {
      val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
      hmppsQueueFactory.createSqsAsyncClient(config, hmppsSqsProperties, outboundQueueSqsDlqClient)
    }

    @Bean("outboundqueue-sqs-dlq-client")
    fun outboundQueueSqsDlqClient(hmppsSqsProperties: HmppsSqsProperties): SqsAsyncClient = with(hmppsSqsProperties) {
      val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
      hmppsQueueFactory.createSqsAsyncDlqClient(config, hmppsSqsProperties)
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
