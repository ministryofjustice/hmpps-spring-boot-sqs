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
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.SqsConfigProperties
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.mainQueue
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration.mocks.OAuthExtension
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageService

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(JwtAuthHelper::class)
@ExtendWith(OAuthExtension::class)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  protected val queueUrl: String by lazy { sqsClient.getQueueUrl(sqsConfigProperties.mainQueue().queueName).queueUrl }
  protected val dlqUrl: String by lazy { sqsDlqClient.getQueueUrl(sqsConfigProperties.mainQueue().dlqName).queueUrl }

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthHelper

  @Autowired
  protected lateinit var sqsClient: AmazonSQS

  @Autowired
  protected lateinit var sqsDlqClient: AmazonSQS

  @SpyBean
  protected lateinit var messageServiceSpy: MessageService

  @SpyBean
  protected lateinit var sqsConfigProperties: SqsConfigProperties

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
