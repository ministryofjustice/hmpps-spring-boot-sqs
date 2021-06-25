package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sqs.AmazonSQS
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.AnotherMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageService
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  fun HmppsQueueProperties.mainQueue() =
    queues["mainQueue"] ?: throw MissingQueueException("main queue has not been loaded from configuration properties")

  fun HmppsQueueProperties.anotherQueue() =
    queues["anotherQueue"] ?: throw MissingQueueException("another queue has not been loaded from configuration properties")

  class MissingQueueException(message: String) : RuntimeException(message)

  private val mainQueue by lazy { hmppsQueueService.findByQueueId("mainQueue") ?: throw MissingQueueException("HmppsQueue mainQueue not found") }
  private val anotherQueue by lazy { hmppsQueueService.findByQueueId("anotherQueue") ?: throw MissingQueueException("HmppsQueue anotherQueue not found") }

  protected val sqsClient by lazy { mainQueue.sqsClient }
  protected val sqsDlqClient by lazy { mainQueue.sqsDlqClient }
  protected val anotherSqsClient by lazy { anotherQueue.sqsClient }
  protected val anotherSqsDlqClient by lazy { anotherQueue.sqsDlqClient }

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
}
