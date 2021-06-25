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
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.anotherQueue
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.mainQueue
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.AnotherMessageService
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageService
import uk.gov.justice.hmpps.sqs.HmppsQueueProperties

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  protected val queueUrl: String by lazy { sqsClient.getQueueUrl(hmppsQueueProperties.mainQueue().queueName).queueUrl }
  protected val dlqUrl: String by lazy { sqsDlqClient.getQueueUrl(hmppsQueueProperties.mainQueue().dlqName).queueUrl }
  protected val anotherQueueUrl: String by lazy { anotherSqsClient.getQueueUrl(hmppsQueueProperties.anotherQueue().queueName).queueUrl }
  protected val anotherDlqUrl: String by lazy { anotherSqsDlqClient.getQueueUrl(hmppsQueueProperties.anotherQueue().dlqName).queueUrl }

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var sqsClient: AmazonSQS

  @Autowired
  protected lateinit var sqsDlqClient: AmazonSQS

  @Autowired
  protected lateinit var anotherSqsClient: AmazonSQS

  @Autowired
  protected lateinit var anotherSqsDlqClient: AmazonSQS

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
