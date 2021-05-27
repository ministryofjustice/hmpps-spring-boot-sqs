package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageRequest
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import org.springframework.stereotype.Service

@Service
class QueueAdminService {

    fun transferAllMessages(request: TransferMessagesRequest): TransferMessagesResult {
        val messageCount = request.from.countMessagesOnQueue(request.fromUrl)
        var messages = mutableListOf<Message>()
        repeat(messageCount) {
            request.from.receiveMessage(ReceiveMessageRequest(request.fromUrl).withMaxNumberOfMessages(1)).messages.firstOrNull()
                ?.let { msg ->
                    request.to.sendMessage(request.toUrl, msg.body)
                    request.from.deleteMessage(DeleteMessageRequest(request.fromUrl, msg.receiptHandle))
                    messages += msg
                }
        }
        return TransferMessagesResult(messageCount, messages.toList())
    }

    private fun AmazonSQS.countMessagesOnQueue(queueUrl: String): Int =
        this.getQueueAttributes(queueUrl, listOf("ApproximateNumberOfMessages"))
            .let { it.attributes["ApproximateNumberOfMessages"]?.toInt() ?: 0 }
}

data class TransferMessagesRequest(val from: AmazonSQS, val fromUrl: String, val to: AmazonSQS, val toUrl: String)
data class TransferMessagesResult(val messagesFoundCount: Int, val messages: List<Message>)
