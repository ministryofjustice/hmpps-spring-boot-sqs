package uk.gov.justice.hmpps.sqs

/** Part of the standard structure of a queue message that has originated from a topic */
data class SnsMessage(
  val Message: String,
  val MessageId: String,
  val MessageAttributes: MessageAttributes,
)

data class MessageAttribute(
  val Type: String,
  val Value: Any?,
)
typealias EventType = MessageAttribute
class MessageAttributes() : HashMap<String, MessageAttribute>() {
  constructor(attribute: EventType) : this() {
    put(attribute.Value.toString(), attribute)
  }

  val eventType: String?
    get() = this["eventType"]?.Value?.toString()
}
