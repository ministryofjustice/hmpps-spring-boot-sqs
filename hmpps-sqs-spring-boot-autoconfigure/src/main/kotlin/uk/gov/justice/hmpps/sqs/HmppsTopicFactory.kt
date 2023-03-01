package uk.gov.justice.hmpps.sqs

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sns.model.CreateTopicRequest

class HmppsTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val healthContributorRegistry: HealthContributorRegistry,
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
          .also { getOrDefaultHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultHealthIndicator(topic: HmppsTopic) {
    runCatching { healthContributorRegistry.getContributor("${topic.id}-health") as HealthIndicator }
      .getOrElse {
        HmppsTopicHealth(topic).also { healthContributorRegistry.registerContributor("${topic.id}-health", it) }
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
        "aws" -> snsClientFactory.awsSnsAsyncClient(topicConfig.accessKeyId, topicConfig.secretAccessKey, region)
        "localstack" -> snsClientFactory.localstackSnsAsyncClient(localstackUrl, region)
          .also { runBlocking { it.createTopic(CreateTopicRequest.builder().name(topicConfig.name).build()).await() } }
          .also { log.info("Created a LocalStack SNS topic for topicId $topicId with ARN ${topicConfig.arn}") }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }
}
