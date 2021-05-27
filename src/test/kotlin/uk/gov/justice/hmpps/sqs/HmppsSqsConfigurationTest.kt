package uk.gov.justice.hmpps.sqs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.test.context.SpringBootTest

@SpringBootApplication
class HmppsSqsConfigurationApplication {
  fun main(args: Array<String>) {
    runApplication<HmppsSqsConfigurationApplication>(*args)
  }
}

@SpringBootTest(classes = [HmppsSqsConfigurationApplication::class])
class HmppsSqsConfigurationTest {

  @Autowired
  private lateinit var sqsQueueAdminService: SqsQueueAdminService

  @Test
  fun `the sqs queue admin service is loaded`() {
    assertThat(sqsQueueAdminService).isNotNull
  }
}
