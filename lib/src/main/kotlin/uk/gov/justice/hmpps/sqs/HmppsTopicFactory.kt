package uk.gov.justice.hmpps.sqs

import com.amazonaws.services.sns.AmazonSNS
import org.springframework.context.ConfigurableApplicationContext
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties.TopicConfig

class HmppsTopicFactory(
  private val context: ConfigurableApplicationContext,
  private val amazonSnsFactory: AmazonSnsFactory,
) {

  fun createHmppsTopics(hmppsSqsProperties: HmppsSqsProperties) =
    hmppsSqsProperties.topics
      .map { (topicId, topicConfig) ->
        val snsClient = getOrDefaultSnsClient(topicId, topicConfig, hmppsSqsProperties)
        HmppsTopic(topicId, snsClient)
        // TODO  .also { getOrDefaultHealthIndicator(it) }
      }.toList()

  private fun getOrDefaultSnsClient(topicId: String, topicConfig: TopicConfig, hmppsSqsProperties: HmppsSqsProperties): AmazonSNS =
    "$topicId-sns-client".let { beanName ->
      runCatching { context.beanFactory.getBean(beanName) as AmazonSNS }
        .getOrElse {
          createSnsClient(topicId, topicConfig, hmppsSqsProperties)
            .also { context.beanFactory.registerSingleton(beanName, it) }
        }
    }

  fun createSnsClient(topicId: String, topicConfig: TopicConfig, hmppsSqsProperties: HmppsSqsProperties) =
    with(hmppsSqsProperties) {
      when (provider) {
        "aws" -> amazonSnsFactory.awsSnsClient(topicId, topicConfig.accessKeyId, topicConfig.secretAccessKey, region, topicConfig.asyncClient)
        "localstack" -> amazonSnsFactory.localstackSnsClient(topicId, localstackUrl, region, topicConfig.asyncClient)
        // TODO .also { // create topic in localstack }
        else -> throw IllegalStateException("Unrecognised HMPPS SQS provider $provider")
      }
    }
}
