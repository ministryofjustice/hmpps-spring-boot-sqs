package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.PurgeQueueRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.applicationinsights.core.dependencies.google.gson.Gson
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.testcontainers.LocalStackContainer
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration.testcontainers.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.InboundMessageService
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.MissingQueueException
import uk.gov.justice.hmpps.sqs.MissingTopicException

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @BeforeEach
  fun `clear queues`() {
    inboundSqsClient.purgeQueue(PurgeQueueRequest(inboundQueueUrl))
    inboundSqsDlqClient.purgeQueue(PurgeQueueRequest(inboundDlqUrl))
  }

  fun HmppsSqsProperties.inboundQueueConfig() =
    queues["inboundqueue"] ?: throw MissingQueueException("inboundqueue has not been loaded from configuration properties")

  fun HmppsSqsProperties.inboundTopicConfig() =
    topics["inboundtopic"] ?: throw MissingTopicException("inboundtopic has not been loaded from configuration properties")

  private val inboundQueue by lazy { hmppsQueueService.findByQueueId("inboundqueue") ?: throw MissingQueueException("HmppsQueue inboundqueue not found") }
  private val inboundTopic by lazy { hmppsQueueService.findByTopicId("inboundtopic") ?: throw MissingQueueException("HmppsTopic inboundtopic not found") }

  protected val inboundSqsClient by lazy { inboundQueue.sqsClient }
  protected val inboundSqsDlqClient by lazy { inboundQueue.sqsDlqClient as AmazonSQS }
  protected val inboundSnsClient by lazy { inboundTopic.snsClient }

  protected val inboundQueueUrl by lazy { inboundQueue.queueUrl }
  protected val inboundDlqUrl by lazy { inboundQueue.dlqUrl as String }

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
  protected lateinit var hmppsSqsPropertiesSpy: HmppsSqsProperties

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

  companion object {
    private val localStackContainer = LocalStackContainer.instance

    @JvmStatic
    @DynamicPropertySource
    fun testcontainers(registry: DynamicPropertyRegistry) {
      localStackContainer?.also { setLocalStackProperties(it, registry) }
    }
  }
}
