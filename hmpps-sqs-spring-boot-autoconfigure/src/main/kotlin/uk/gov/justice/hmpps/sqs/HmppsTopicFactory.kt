package uk.gov.justice.hmpps.sqs

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.CreateTopicRequest
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig

class HmppsTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val snsClientFactory: SnsClientFactory,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createHmppsTopics(hmppsSqsProperties: HmppsSqsProperties) =
    hmppsSqsProperties.topics
      .filter { (_, topicConfig) -> !topicConfig.asyncClient }
      .map { (topicId, topicConfig) ->
        val snsClient = getOrDefaultSnsClient(topicId, topicConfig, hmppsSqsProperties)
        HmppsTopic(topicId, topicConfig.arn, snsClient)
          .also { getOrDefaultHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultHealthIndicator(topic: HmppsTopic) {
    "${topic.id}-health".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as SnsClient }
        .getOrElse {
          HmppsTopicHealth(topic)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }
  }

  private fun getOrDefaultSnsClient(topicId: String, topicConfig: TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsClient =
    "$topicId-sns-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as SnsClient }
        .getOrElse {
          createSnsClient(topicId, topicConfig, hmppsSqsProperties)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  fun createSnsClient(topicId: String, topicConfig: TopicConfig, hmppsSqsProperties: HmppsSqsProperties): SnsClient =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> snsClientFactory.awsSnsClient(topicConfig.accessKeyId, topicConfig.secretAccessKey, region)
        "localstack" -> snsClientFactory.localstackSnsClient(localstackUrl, region)
          .also { it.createTopic(CreateTopicRequest.builder().name(topicConfig.name).build()) }
          .also { log.info("Created a LocalStack SNS topic for topicId $topicId with ARN ${topicConfig.arn}") }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }
}
