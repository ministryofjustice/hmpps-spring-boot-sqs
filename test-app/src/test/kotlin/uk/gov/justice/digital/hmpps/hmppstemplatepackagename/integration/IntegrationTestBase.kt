package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.PurgeQueueRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.core.dependencies.google.gson.Gson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
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
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS
import org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.InboundMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundEventsEmitter
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.OutboundMessageService
import uk.gov.justice.hmpps.sqs.HmppsQueueFactory
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.MissingQueueException
import uk.gov.justice.hmpps.sqs.MissingTopicException
import java.io.IOException
import java.net.ServerSocket

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(IntegrationTestBase.SqsConfig::class, JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @BeforeEach
  fun `clear queues`() {
    inboundSqsClient.purgeQueue(PurgeQueueRequest(inboundQueueUrl))
    inboundSqsDlqClient.purgeQueue(PurgeQueueRequest(inboundDlqUrl))
    outboundSqsClientSpy.purgeQueue(PurgeQueueRequest(outboundQueueUrl))
    outboundSqsDlqClientSpy.purgeQueue(PurgeQueueRequest(outboundDlqUrl))
  }

  fun HmppsSqsProperties.inboundQueueConfig() =
    queues["inboundqueue"] ?: throw MissingQueueException("inboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundQueueConfig() =
    queues["outboundqueue"] ?: throw MissingQueueException("outboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.inboundTopicConfig() =
    topics["inboundtopic"] ?: throw MissingTopicException("inboundtopic has not been loaded from configuration properties")

  fun HmppsSqsProperties.outboundTopicConfig() =
    topics["outboundtopic"] ?: throw MissingTopicException("outboundtopic has not been loaded from configuration properties")

  private val inboundQueue by lazy { hmppsQueueService.findByQueueId("inboundqueue") ?: throw MissingQueueException("HmppsQueue inboundqueue not found") }
  private val outboundQueue by lazy { hmppsQueueService.findByQueueId("outboundqueue") ?: throw MissingQueueException("HmppsQueue outboundqueue not found") }
  private val outboundTestQueue by lazy { hmppsQueueService.findByQueueId("outboundtestqueue") ?: throw MissingQueueException("HmppsQueue outboundtestqueue not found") }
  private val inboundTopic by lazy { hmppsQueueService.findByTopicId("inboundtopic") ?: throw MissingQueueException("HmppsTopic inboundtopic not found") }

  protected val inboundSqsClient by lazy { inboundQueue.sqsClient }
  protected val inboundSqsDlqClient by lazy { inboundQueue.sqsDlqClient!! }
  protected val inboundSnsClient by lazy { inboundTopic.snsClient }
  protected val outboundTestSqsClient by lazy { outboundTestQueue.sqsClient }

  @SpyBean
  @Qualifier("outboundqueue-sqs-client")
  protected lateinit var outboundSqsClientSpy: AmazonSQS

  @SpyBean
  @Qualifier("outboundqueue-sqs-dlq-client")
  protected lateinit var outboundSqsDlqClientSpy: AmazonSQS

  protected val inboundQueueUrl: String by lazy { inboundQueue.queueUrl }
  protected val inboundDlqUrl: String by lazy { inboundQueue.dlqUrl!! }
  protected val outboundQueueUrl: String by lazy { outboundQueue.queueUrl }
  protected val outboundDlqUrl: String by lazy { outboundQueue.dlqUrl!! }
  protected val outboundTestQueueUrl: String by lazy { outboundTestQueue.queueUrl }

  protected val inboundTopicArn by lazy { inboundTopic.arn }

  @Autowired
  protected lateinit var objectMapper: ObjectMapper

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var hmppsQueueService: HmppsQueueService

  @SpyBean
  protected lateinit var inboundMessageServiceSpy: InboundMessageService

  @SpyBean
  protected lateinit var outboundMessageServiceSpy: OutboundMessageService

  @SpyBean
  protected lateinit var hmppsSqsPropertiesSpy: HmppsSqsProperties

  @SpyBean
  protected lateinit var outboundEventsEmitterSpy: OutboundEventsEmitter

  @Suppress("SpringJavaInjectionPointsAutowiringInspection")
  @Autowired
  lateinit var webTestClient: WebTestClient

  internal fun AmazonSQS.countMessagesOnQueue(queueUrl: String): Int =
    this.getQueueAttributes(queueUrl, listOf("ApproximateNumberOfMessages"))
      .let { it.attributes["ApproximateNumberOfMessages"]?.toInt() ?: 0 }

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
      @Qualifier("outboundqueue-sqs-dlq-client") outboundQueueSqsDlqClient: AmazonSQS
    ): AmazonSQS =
      with(hmppsSqsProperties) {
        val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
        hmppsQueueFactory.createSqsClient("outboundqueue", config, hmppsSqsProperties, outboundQueueSqsDlqClient)
      }

    @Bean("outboundqueue-sqs-dlq-client")
    fun outboundQueueSqsDlqClient(hmppsSqsProperties: HmppsSqsProperties): AmazonSQS =
      with(hmppsSqsProperties) {
        val config = queues["outboundqueue"] ?: throw MissingQueueException("HmppsSqsProperties config for outboundqueue not found")
        hmppsQueueFactory.createSqsDlqClient("outboundqueue", config, hmppsSqsProperties)
      }
  }

  companion object {
    val log = LoggerFactory.getLogger(this::class.java)

    @JvmStatic
    @DynamicPropertySource
    fun testcontainers(registry: DynamicPropertyRegistry) {
      startLocalstackIfNotRunning()?.run {
        getEndpointConfiguration(SNS)
          .let { it.serviceEndpoint to it.signingRegion }
          .also {
            registry.add("hmpps.sqs.localstackUrl") { it.first }
            registry.add("hmpps.sqs.region") { it.second }
          }
      }
    }

    private fun startLocalstackIfNotRunning(): LocalStackContainer? {
      if (localstackIsRunning()) return null
      val logConsumer = Slf4jLogConsumer(log).withPrefix("localstack")
      return LocalStackContainer(
        DockerImageName.parse("localstack/localstack").withTag("0.12.9.1")
      ).apply {
        withServices(SNS, SQS)
        withEnv("HOSTNAME_EXTERNAL", "localhost")
        withEnv("DEFAULT_REGION", "eu-west-2")
        waitingFor(
          Wait.forLogMessage(".*Ready.*", 1)
        )
        start()
        followOutput(logConsumer)
      }
    }

    private fun localstackIsRunning(): Boolean =
      try {
        val serverSocket = ServerSocket(4566)
        serverSocket.localPort == 0
      } catch (e: IOException) {
        true
      }
  }
}
