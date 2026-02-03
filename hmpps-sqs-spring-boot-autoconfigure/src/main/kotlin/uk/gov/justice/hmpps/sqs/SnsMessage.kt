package uk.gov.justice.hmpps.sqs

import com.fasterxml.jackson.annotation.JsonProperty

/** Part of the standard structure of a queue message that has originated from a topic */
data class SnsMessage(
  /** The payload of the message */
  @field:JsonProperty("Message")
  val message: String,
  /** Unique identifier for the message */
  @field:JsonProperty("MessageId")
  val messageId: String,
  /** Attributes of the message. One of these attributes should be eventType. */
  @field:JsonProperty("MessageAttributes")
  val messageAttributes: MessageAttributes,
)

data class MessageAttribute(
  /** The data type of the message attribute, often String. */
  @field:JsonProperty("Type")
  val type: String,
  @field:JsonProperty("Value")
  val value: Any?,
)

class MessageAttributes : HashMap<String, MessageAttribute>() {
  val eventType: String?
    get() = this["eventType"]?.value?.toString()
}
