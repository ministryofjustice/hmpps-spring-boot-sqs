package uk.gov.justice.hmpps.sqs

class HmppsTopicService(
  hmppsTopicFactory: HmppsTopicFactory,
  hmppsSqsProperties: HmppsSqsProperties,
) {

  val hmppsTopics: List<HmppsTopic> = hmppsTopicFactory.createHmppsTopics(hmppsSqsProperties)

  fun findByTopicId(topicId: String) = hmppsTopics.associateBy { it.id }.getOrDefault(topicId, null)
}
