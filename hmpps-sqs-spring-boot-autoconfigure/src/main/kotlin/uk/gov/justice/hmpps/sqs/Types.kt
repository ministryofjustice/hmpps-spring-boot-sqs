package uk.gov.justice.hmpps.sqs

enum class Provider(val code: String) {
  AWS("aws"),
  LOCALSTACK("localstack"),
}
fun findProvider(code: String) = enumValues<Provider>().firstOrNull { it.code == code } ?: throw InvalidProviderType(code)

class MissingDlqNameException() : RuntimeException("Attempted to access dlq but no name has been set")
class InvalidProviderType(val provider: String) : RuntimeException("Unknown provider type: $provider. Expected one of: ${Provider.values().map { it.code }}")

class MissingRedrivePolicyException(queueId: String) : RuntimeException("The main queue for $queueId is missing a RedrivePolicy")
class MissingDlqClientException(dlqName: String?) : RuntimeException("Attempted to access dlqclient for $dlqName that does not exist")
