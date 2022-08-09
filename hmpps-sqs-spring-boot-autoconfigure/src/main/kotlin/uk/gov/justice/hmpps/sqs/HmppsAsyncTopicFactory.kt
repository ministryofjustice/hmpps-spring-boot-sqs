package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreateTopicRequest

class HmppsAsyncTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val snsAsyncClientFactory: SnsAsyncClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsAsyncTopics(hmppsSqsProperties: HmppsSqsProperties) =
    hmppsSqsProperties.topics
      .filter { (_, topicConfig) -> topicConfig.asyncClient }
      .map { (topicId, topicConfig) ->
        val snsClient = getOrDefaultSnsAsyncClient(topicId, topicConfig, hmppsSqsProperties)
        HmppsAsyncTopic(topicId, topicConfig.arn, snsClient)
          .also { getOrDefaultHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultHealthIndicator(topic: HmppsAsyncTopic) {
    "${topic.id}-health".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as SnsAsyncClient }
        .getOrElse {
          HmppsAsyncTopicHealth(topic)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }
  }

  private fun getOrDefaultSnsAsyncClient(topicId: String, topicConfig: HmppsSqsProperties.TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsAsyncClient =
    "$topicId-sns-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as SnsAsyncClient }
        .getOrElse {
          createSnsAsyncClient(topicId, topicConfig, hmppsSqsProperties)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  fun createSnsAsyncClient(topicId: String, topicConfig: HmppsSqsProperties.TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsAsyncClient =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> snsAsyncClientFactory.awsSnsAsyncClient(topicConfig.accessKeyId, topicConfig.secretAccessKey, region)
        "localstack" -> snsAsyncClientFactory.localstackSnsAsyncClient(localstackUrl, region)
          .also {
            it.createTopic(CreateTopicRequest.builder().name(topicConfig.name).build())
              .thenRun { log.info("Created a LocalStack SNS topic for topicId $topicId with ARN ${topicConfig.arn}") } // TODO handle the error?
          }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }
}
