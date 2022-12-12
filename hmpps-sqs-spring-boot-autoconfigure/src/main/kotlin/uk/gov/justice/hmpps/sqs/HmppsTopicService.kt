package uk.gov.justice.hmpps.sqs

class HmppsTopicService(
  hmppsTopicFactory: HmppsTopicFactory,
  hmppsAsyncTopicFactory: HmppsAsyncTopicFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  val hmppsTopics: List<HmppsTopic> = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)
  val hmppsAsyncTopics: List<HmppsAsyncTopic> = hmppsAsyncTopicFactory.createHmppsAsyncTopics(hmppsSqsProperties)

  fun findByTopicId(topicId: String) = hmppsTopics.associateBy { it.id }.getOrDefault(topicId, null)
  fun findAsyncByTopicId(topicId: String) = hmppsAsyncTopics.associateBy { it.id }.getOrDefault(topicId, null)
}
