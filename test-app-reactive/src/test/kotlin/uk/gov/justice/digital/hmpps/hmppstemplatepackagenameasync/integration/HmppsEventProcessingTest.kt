package uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.integration

import com.sun.org.apache.xml.internal.serializer.utils.Utils.messages
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.HmppsEvent
import uk.gov.justice.digital.hmpps.hmppstemplatepackagenameasync.service.Message
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.countMessagesOnQueue
import uk.gov.justice.hmpps.sqs.publish
import java.io.File
import java.util.UUID

class HmppsEventProcessingTest : IntegrationTestBase() {

  @Autowired
  lateinit var s3Client: S3AsyncClient

  @Test
  fun `event is published to outbound topic`() = runTest {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get() } matches { it == 1 }

    val (message) = objectMapper.readValue(outboundTestSqsClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(outboundTestQueueUrl).build()).get().messages()[0].body(), Message::class.java)
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is published to outbound topic but the test queue subscriber ignores it`() {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-DISCHARGE", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { mockingDetails(outboundEventsEmitterSpy).invocations!! } matches { it?.isNotEmpty() ?: false } // Don't understand why it is nullable here

    assertThat(outboundTestSqsClient.countMessagesOnQueue(outboundTestQueueUrl).get()).isEqualTo(0)
  }

  @Test
  fun `event is published to outbound topic received by queue with no dlq`() = runTest {
    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder().topicArn(inboundTopicArn).message(gsonString(event)).messageAttributes(
        mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
      ).build(),
    )

    await untilCallTo { outboundTestNoDlqSqsClient.countMessagesOnQueue(outboundTestNoDlqQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(outboundTestNoDlqQueueUrl).build()
      .let { outboundTestNoDlqSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("event-id")
    assertThat(receivedEvent.type).isEqualTo("offender.movement.reception")
    assertThat(receivedEvent.contents).isEqualTo("some event contents")
  }

  @Test
  fun `event is moved to the dead letter queue when an exception is thrown`() = runTest {
    doThrow(RuntimeException("some error")).whenever(inboundMessageServiceSpy).handleMessage(any())

    val event = HmppsEvent("event-id", "OFFENDER_MOVEMENT-RECEPTION", "some event contents")
    inboundSnsClient.publish(
      PublishRequest.builder()
        .topicArn(inboundTopicArn)
        .message(gsonString(event))
        .messageAttributes(
          mapOf("eventType" to MessageAttributeValue.builder().dataType("String").stringValue(event.type).build()),
        )
        .build(),
    )

    await untilCallTo { inboundSqsDlqClient.countMessagesOnQueue(inboundDlqUrl).get() } matches { it == 1 }
    assertThat(inboundSqsClient.countAllMessagesOnQueue(inboundQueueUrl).get()).isEqualTo(0)
  }

  @Test
  fun `event published to fifo topic is received by fifo queue`() = runTest {
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents")
    fifoTopic.publish(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
    )

    await untilCallTo { fifoSqsClient.countMessagesOnQueue(fifoQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(fifoQueueUrl).build()
      .let { fifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("fifo-event-id")
    assertThat(receivedEvent.type).isEqualTo("FIFO-EVENT")
    assertThat(receivedEvent.contents).isEqualTo("some FIFO contents")
  }

//  You might be wondering why this test does not use the SqsExtendedClient to consume the message
  // We believe there is a bug in Localstack which means that the shape of the message published by the SnsExtendedClient
  // does not match what is expected by the SqsExtendedClient
  // Expected format (messageAttributes are at the top level):
//  {
//    "Messages": [
//    {
//      "MessageId": "62e3d70d-02e0-4764-9002-2938fd3baf5f",
//      "ReceiptHandle": "Mzk0N2I2NzUtMmE5Ny00YzkwLWIxN2YtODYyYjU1YTA2NjZkIGFybjphd3M6c3FzOmV1LXdlc3QtMjowMDAwMDAwMDAwMDA6bGFyZ2VtZXNzYWdlZmlmb3F1ZXVldGVzdGFwcC5maWZvIDYyZTNkNzBkLTAyZTAtNDc2NC05MDAyLTI5MzhmZDNiYWY1ZiAxNzQyMzgzODAxLjYyNzExOA==",
//      "MD5OfBody": "1d04d5db5b48aa2b65ec1b62ae19dd51",
//      "Body": "[\"software.amazon.payloadoffloading.PayloadS3Pointer\",{\"s3BucketName\":\"bucket\",\"s3Key\":\"1638422c-ea5d-4d15-85d6-ff616977e002\"}]",
//      "MD5OfMessageAttributes": "8eea9c249ef9487b98a44b8921c1452d",
//      "MessageAttributes": {
//      "SQSLargePayloadSize": {
//      "StringValue": "817330",
//      "DataType": "Number"
//    }
//    }
//    }
//    ]
//  }

// Actual format (messageAttributes end up in the body)
// {
//  "Messages": [
//    {
//      "MessageId": "5c3bc082-9ddd-4eaf-a968-7207be779dfb",
//      "ReceiptHandle": "YTQ5OTkwODktMGU3YS00NzM4LWFjNDItNThiNDlkNjExMWI2IGFybjphd3M6c3FzOmV1LXdlc3QtMjowMDAwMDAwMDAwMDA6bGFyZ2VtZXNzYWdlZmlmb3F1ZXVldGVzdGFwcC5maWZvIDVjM2JjMDgyLTlkZGQtNGVhZi1hOTY4LTcyMDdiZTc3OWRmYiAxNzQyMzEzNDQ4LjE3MzY0NDg=",
//      "MD5OfBody": "2c0346aaea88652a443af89f57fd599d",
//      "Body": "{\"Type\": \"Notification\",
//      \"MessageId\": \"76a50eba-5ad3-4132-b1c7-a3c08c89eb5a\",
//       \"TopicArn\": \"arn:aws:sns:eu-west-2:000000000000:5cc40f3c-3ef2-436a-8ce6-81983644d888.fifo\",
//        \"Message\": \"[\\\"software.amazon.payloadoffloading.PayloadS3Pointer\\\",{\\\"s3BucketName\\\":\\\"bucket-name\\\",\\\"s3Key\\\":\\\"95d6412e-6d42-425c-8f56-a73162f59b41\\\"}]\",
//         \"Timestamp\": \"2025-03-18T15:54:30.890Z\",
//         \"UnsubscribeURL\": \"http://localhost.localstack.cloud:4566/?Action=Unsubscribe&SubscriptionArn=arn:aws:sns:eu-west-2:000000000000:5cc40f3c-3ef2-436a-8ce6-81983644d888.fifo:578f9c25-5f2b-49e3-804a-3eac5a6e0751\",
// >>>>>>>>>>          \"MessageAttributes\": {\"eventType\": {\"Type\": \"String\", \"Value\": \"FIFO-EVENT\"}, \"ExtendedPayloadSize\": {\"Type\": \"Number\", \"Value\": \"859754\"}}, \"SequenceNumber\": \"14966357887067095040\"}",
//      "Attributes": {
//        "SenderId": "000000000000",
//        "SentTimestamp": "1742313270908",
//        "MessageGroupId": "1cdb437a-4a49-4c55-85f5-2ec27ab714e6",
//        "MessageDeduplicationId": "df076b27ac29557174652d812ccba96f244a76a420fc9587655c3865cafcff77",
//        "SequenceNumber": "14966357028073635840",
//        "ApproximateReceiveCount": "1",
//        "ApproximateFirstReceiveTimestamp": "1742313448173"
//      }
//    }
//  ]
// }
  @Test
  fun `large message pointer is published to topic and message body stored in S3`() = runTest {
    val file = File("src/test/resources/events/large-sns-message.json").bufferedReader().readLines()
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", file.toString())
    largeMessageFifoTopic.publish(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
    )

    await untilCallTo { largeMessageFifoSqsClient.countMessagesOnQueue(largeMessageFifoQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(largeMessageFifoQueueUrl).build()
      .let { largeMessageFifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }

    val s3Reference: java.util.ArrayList<*> = objectMapper.readValue(message, ArrayList::class.java)
    val s3Pointer = objectMapper.readValue(objectMapper.writeValueAsString(s3Reference[1]), LinkedHashMap::class.java)
    assertThat(s3Pointer.get("s3BucketName")).isEqualTo("bucket-name")
    assertThat(s3Pointer.keys).contains("s3Key")
    val s3Object = s3Client.getObject(
      GetObjectRequest.builder().bucket("bucket-name").key(s3Pointer.get("s3Key").toString()).build(),
      AsyncResponseTransformer.toBytes(),
    ).join()
    assertThat(s3Object.asUtf8String()).contains("large message ID")
  }

  @Test
  fun `large message stored in S3 can be consumed with extended client`() = runTest {
    val file = File("src/test/resources/events/large-sns-message.json").bufferedReader().readLines()

    largeMessageFifoSqsClient
      .sendMessage(
        SendMessageRequest.builder()
          .queueUrl(largeMessageFifoQueueUrl)
          .messageDeduplicationId("dedube")
          .messageBody(file.toString())
          .messageGroupId("groupId")
          .build(),
      )
      .await()

    await untilCallTo { largeMessageFifoSqsClient.countMessagesOnQueue(largeMessageFifoQueueUrl).get() } matches { it == 1 }

    val message = ReceiveMessageRequest.builder().queueUrl(largeMessageFifoQueueUrl).build()
      .let { largeMessageFifoSqsClient.receiveMessage(it).get().messages()[0].body() }

    assertThat(message).contains("large message ID")
  }

  @Test
  fun `small event published to large message fifo topic is not stored in S3`() = runTest {
    val event = HmppsEvent("fifo-event-id", "FIFO-EVENT", "some FIFO contents")
    largeMessageFifoTopic.publish(
      eventType = event.type,
      event = gsonString(event),
      messageGroupId = UUID.randomUUID().toString(),
    )

    await untilCallTo { largeMessageFifoSqsClient.countMessagesOnQueue(largeMessageFifoQueueUrl).get() } matches { it == 1 }

    val (message) = ReceiveMessageRequest.builder().queueUrl(largeMessageFifoQueueUrl).build()
      .let { largeMessageFifoSqsClient.receiveMessage(it).get().messages()[0].body() }
      .let { objectMapper.readValue(it, Message::class.java) }
    val receivedEvent = objectMapper.readValue(message, HmppsEvent::class.java)

    assertThat(receivedEvent.id).isEqualTo("fifo-event-id")
    assertThat(receivedEvent.type).isEqualTo("FIFO-EVENT")
    assertThat(receivedEvent.contents).isEqualTo("some FIFO contents")
  }
}
