package uk.gov.justice.digital.hmpps.templatepackagenameasync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ReactiveApp

fun main(args: Array<String>) {
  runApplication<ReactiveApp>(*args)
}
