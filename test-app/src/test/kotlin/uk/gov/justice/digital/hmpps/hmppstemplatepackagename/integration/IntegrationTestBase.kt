package uk.gov.justice.digital.hmpps.hmppstemplatepackagename.integration

import com.amazonaws.services.sqs.AmazonSQS
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.config.SqsConfigProperties
import uk.gov.justice.digital.hmpps.hmppstemplatepackagename.service.MessageService

@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  protected val queueUrl: String by lazy { sqsClient.getQueueUrl(sqsConfigProperties.queueName).queueUrl }
  protected val dlqUrl: String by lazy { sqsDlqClient.getQueueUrl(sqsConfigProperties.dlqName).queueUrl }

  @Autowired
  protected lateinit var sqsClient: AmazonSQS

  @Autowired
  protected lateinit var sqsDlqClient: AmazonSQS

  @SpyBean
  protected lateinit var messageServiceSpy: MessageService

  @SpyBean
  private lateinit var sqsConfigProperties: SqsConfigProperties

  @Suppress("SpringJavaInjectionPointsAutowiringInspection")
  @Autowired
  lateinit var webTestClient: WebTestClient
}
