package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.PurgeQueueRequest
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
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.AnotherMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageService
import uk.gov.justice.hmpps.sqs.HmppsQueueFactory
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.MissingQueueException

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(IntegrationTestBase.SqsConfig::class, JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @BeforeEach
  fun `clear queues`() {
    sqsClient.purgeQueue(PurgeQueueRequest(queueUrl))
    sqsDlqClient.purgeQueue(PurgeQueueRequest(dlqUrl))
    anotherSqsClient.purgeQueue(PurgeQueueRequest(anotherQueueUrl))
    anotherSqsDlqClient.purgeQueue(PurgeQueueRequest(anotherDlqUrl))
  }

  fun HmppsQueueProperties.mainQueueConfig() =
    queues["mainqueue"] ?: throw MissingQueueException("mainqueue has not been loaded from configuration properties")

  fun HmppsQueueProperties.anotherQueueConfig() =
    queues["anotherqueue"] ?: throw MissingQueueException("anotherqueue has not been loaded from configuration properties")

  private val mainQueue by lazy { hmppsQueueService.findByQueueId("mainqueue") ?: throw MissingQueueException("HmppsQueue mainqueue not found") }
  private val anotherQueue by lazy { hmppsQueueService.findByQueueId("anotherqueue") ?: throw MissingQueueException("HmppsQueue anotherqueue not found") }

  protected val sqsClient by lazy { mainQueue.sqsClient }
  protected val sqsDlqClient by lazy { mainQueue.sqsDlqClient }

  @SpyBean
  @Qualifier("anotherqueue-sqs-client")
  protected lateinit var anotherSqsClient: AmazonSQS

  @SpyBean
  @Qualifier("anotherqueue-sqs-dlq-client")
  protected lateinit var anotherSqsDlqClient: AmazonSQS

  protected val queueUrl: String by lazy { mainQueue.queueUrl }
  protected val dlqUrl: String by lazy { mainQueue.dlqUrl }
  protected val anotherQueueUrl: String by lazy { anotherQueue.queueUrl }
  protected val anotherDlqUrl: String by lazy { anotherQueue.dlqUrl }

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var hmppsQueueService: HmppsQueueService

  @SpyBean
  protected lateinit var messageServiceSpy: MessageService

  @SpyBean
  protected lateinit var anotherMessageServiceSpy: AnotherMessageService

  @SpyBean
  protected lateinit var hmppsQueueProperties: HmppsQueueProperties

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

  @TestConfiguration
  class SqsConfig(private val hmppsQueueFactory: HmppsQueueFactory) {

    @Bean("anotherqueue-sqs-client")
    fun anotherQueueSqsClient(
      hmppsQueueProperties: HmppsQueueProperties,
      @Qualifier("anotherqueue-sqs-dlq-client") anotherQueueSqsDlqClient: AmazonSQS
    ): AmazonSQS =
      with(hmppsQueueProperties) {
        val config = queues["anotherqueue"] ?: throw MissingQueueException("HmppsQueueProperties config for anotherqueue not found")
        hmppsQueueFactory.localStackSqsClient(config.queueName, config.dlqName, localstackUrl, region, anotherQueueSqsDlqClient)
      }

    @Bean("anotherqueue-sqs-dlq-client")
    fun anotherQueueSqsDlqClient(hmppsQueueProperties: HmppsQueueProperties): AmazonSQS =
      with(hmppsQueueProperties) {
        val config = queues["anotherqueue"] ?: throw MissingQueueException("HmppsQueueProperties config for anotherqueue not found")
        hmppsQueueFactory.localStackSqsDlqClient(config.dlqName, localstackUrl, region)
      }
  }
}
