package uk.gov.justice.digital.hmpps.hmppstemplatepackagename

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class App

fun main(args: Array<String>) {
  runApplication<App>(*args)
}
