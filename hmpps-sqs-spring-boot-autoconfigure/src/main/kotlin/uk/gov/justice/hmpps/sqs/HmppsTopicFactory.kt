package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreateTopicRequest

class HmppsTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val healthContributorRegistry: HmppsHealthContributorRegistry,
  private val snsClientFactory: SnsClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsTopics(hmppsSqsProperties: HmppsSqsProperties) =
    hmppsSqsProperties.topics
      .map { (topicId, topicConfig) ->
        val snsClient = getOrDefaultSnsAsyncClient(topicId, topicConfig, hmppsSqsProperties)
        HmppsTopic(topicId, topicConfig.arn, snsClient)
          .also { registerHealthIndicator(it) }
      }.toList()

  private fun registerHealthIndicator(topic: HmppsTopic) {
    healthContributorRegistry.registerContributor("${topic.id}-health") {
      HmppsTopicHealth(topic)
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
        "aws" -> snsClientFactory.awsSnsAsyncClient(topicConfig.accessKeyId, topicConfig.secretAccessKey, region, hmppsSqsProperties.useWebToken, topicConfig.propagateTracing)
        "localstack" -> snsClientFactory.localstackSnsAsyncClient(localstackUrl, region, topicConfig.propagateTracing)
          .also {
            runBlocking {
              val attributes = when {
                topicConfig.fifoTopic -> mapOf("FifoTopic" to "true", "ContentBasedDeduplication" to topicConfig.contentBasedDeduplication) else -> mapOf()
              }
              it.createTopic(
                CreateTopicRequest.builder()
                  .name(topicConfig.name)
                  .attributes(attributes)
                  .build(),
              ).await()
            }
          }
          .also { log.info("Created a LocalStack SNS topic for topicId $topicId with ARN ${topicConfig.arn}") }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }
}
