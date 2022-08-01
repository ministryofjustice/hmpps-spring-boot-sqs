package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class HmppsTemplateKotlin

fun main(args: Array<String>) {
  runApplication<HmppsTemplateKotlin>(*args)
}
