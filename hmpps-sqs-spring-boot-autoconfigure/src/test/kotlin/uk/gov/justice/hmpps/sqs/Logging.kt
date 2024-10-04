package uk.gov.justice.hmpps.sqs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

fun <T> findLogAppender(javaClass: Class<in T>): ListAppender<ILoggingEvent> = ListAppender<ILoggingEvent>().apply {
  (LoggerFactory.getLogger(javaClass) as Logger).addAppender(this)
  start()
}

fun ListAppender<ILoggingEvent>.formattedMessages() = list.map { it.formattedMessage }
