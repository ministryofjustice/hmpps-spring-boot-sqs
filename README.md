# HMPPS Spring Boot SQS / SNS

A Spring Boot starter library providing utilities for using Amazon Simple Queue Service (SQS) and Simple Notification
Service (SNS). The library is very opinionated towards usage within HMPPS, e.g. we assume that each queue has its own
secrets rather than sharing access between queues.

## Overview

We have many services that use AWS SQS queues and topics with various patterns for managing queues that have evolved
over time. These patterns have been duplicated widely and thus are subject to the usual problems associated with a
lack of DRY such as code drift and the proliferation of boilerplate code.

This library is intended to capture the most common patterns and make them easy to distribute among other projects.
The goal is to provide various queue management and configuration tasks, message publishing/sending helpers, and
dead-letter-queue admin tooling out of the box.

The library relies on
[Spring Boot Auto-configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.auto-configuration)
based upon [configuration properties](#hmppssqsproperties-definitions).

## Release Notes

Check these before upgrading — later major versions in particular contain breaking changes that are worth knowing
about up front.

##### [7.x](release-notes/7.x.md)

##### [6.x](release-notes/6.x.md)

##### [5.x](release-notes/5.x.md)

##### [4.x](release-notes/4.x.md)

##### [3.x](release-notes/3.x.md)

##### [2.x](release-notes/2.x.md)

##### [1.x](release-notes/1.x.md)

## Getting Started

Find the latest published version of the library by searching on Maven Central for `hmpps-spring-boot-sqs`. (If you
can't find the version mentioned in `build.gradle.kts` please be patient, it can take a while to publish to Maven
Central).

Add the following dependency to your Gradle build script:

``` kotlin
implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:<library-version>")
```

### Quick Start

A minimal end-to-end example: configure one queue subscribed to one topic, publish an event onto the topic, and
listen for it on the queue.

#### Configuration

Each queue and topic is configured under `hmpps.sqs` (see
[HmppsSqsProperties Definitions](#hmppssqsproperties-definitions) for every available property), but *where* that
configuration lives differs between integration tests and deployed environments:

* **Integration tests against LocalStack** — configure everything inline in `application-test.yaml`, as this is used
  directly to create the queues/topics/subscriptions in LocalStack:

  ```yaml
  hmpps.sqs:
    provider: localstack
    queues:
      myqueue:
        queueName: my-queue
        dlqName: my-queue-dlq
        subscribeTopicId: domainevents
    topics:
      domainevents:
        arn: arn:aws:sns:eu-west-2:000000000000:domain-events-topic
  ```

* **Deployed environments (dev/preprod/prod)** — queue/topic names and ARNs are real AWS resource identifiers, so
  they must never be hardcoded in `application.yaml`. Instead:
  1. The actual SQS queue/DLQ names and SNS topic ARNs are provisioned by Terraform and land as Kubernetes secrets in
     your namespace (see your service's resources in the
     [cloud-platform-environments](https://github.com/ministryofjustice/cloud-platform-environments) repository).
  2. Your service's `helm_deploy/<service>/values.yaml` maps each of those Kubernetes secret keys onto an
     `HMPPS_SQS_QUEUES_<queueId>_...`/`HMPPS_SQS_TOPICS_<topicId>_...` environment variable (using the generic-service
     chart's `namespace_secrets` block). For topics, use `HMPPS_SQS_TOPICS_<topicId>_ARN` to bind
     `TopicConfig.arn`.
  3. Spring Boot's relaxed binding then maps those environment variables onto the matching `hmpps.sqs.queues.<queueId>.*`/
     `hmpps.sqs.topics.<topicId>.*` properties automatically — there's no equivalent YAML to write for these.

  Any `hmpps.sqs` properties that aren't secret (e.g. `provider`, `dlqMaxReceiveCount`) can still be set directly in
  `application.yaml`.

Publish an event onto the topic using [`HmppsTopic.publish`](#publishing-to-an-sns-topic-hmppstopicpublish):

```kotlin
@Service
class EventPublisher(hmppsQueueService: HmppsQueueService, private val jsonMapper: JsonMapper) {
  private val domainEventsTopic = hmppsQueueService.findByTopicId("domainevents") as HmppsTopic

  fun publish(event: MyEvent) {
    domainEventsTopic.publish(
      eventType = "my.event.happened",
      event = jsonMapper.writeValueAsString(event),
    )
  }
}
```

Listen for it on the queue using [`@SqsListener`](#sqslistener):

```kotlin
@Service
class EventListener(private val jsonMapper: JsonMapper) {

  @SqsListener("myqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: SnsMessage) {
    val event: MyEvent = jsonMapper.readValue(message.message)
    // ... handle event
  }
}
```

That's it — you now also get, for free: a `HealthIndicator` for the queue/topic on your `/health` page, queue admin
endpoints for retrying/purging/inspecting the DLQ, `SqsAsyncClient`/`SnsAsyncClient` beans (wired for LocalStack when 
testing and for AWS in production), distributed tracing of the published/received messages, and configurable DLQ retry
timing. Each of these is covered in detail in [Features](#features) below.

For a fuller worked example (including an audit queue, FIFO queues, and both reactive and non-reactive variants) see
the [test-app](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/tree/main/test-app).

## How To Run This Locally

See [Running Locally](readme-docs/RunningLocally.md)

## Features

### HMPPS Queue Properties

This library is driven by some configuration properties prefixed `hmpps.sqs` that are loaded into class
`HmppsSqsProperties`. Based on the properties defined the library will attempt to:

* create `SqsAsyncClient` beans for each queue defined which are configured for AWS (or LocalStack for testing / running
  locally)
* create `SnsAsyncClient` beans for each topic defined which are configured for AWS (or LocalStack for testing / running
  locally)
* create a `HealthIndicator` for each queue and topic which is registered with Spring Boot Actuator and appears on
  your `/health` page
* add `HmppsQueueResource` to the project if at least one non audit queue is defined, which provides endpoints for
  retrying/inspecting DLQ messages and purging queues
* create a SQS listener connection factory for each queue defined
* create LocalStack queues and topics for testing against, and subscribe queues to topics where configured
* configure the time between retries at the project, queue and event type levels

Examples of property usage can be found in the test project in the following places:

*
  Production: [test-app/.../values.yaml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/values.yaml#L33)
* Running locally with
  LocalStack: [test-app/.../application-localstack.yml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/resources/application-localstack.yml)
* Integration
  Test: [test-app/.../application-test.yml#L8](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml#L8)

#### Audit queue

A queue with an `id` of `audit` is considered to be the HMPPS Audit queue. As such, a `HmppsQueueResource` will not be
added to the project if that is the only queue defined. If at least one non audit queue is defined then the
`HmppsQueueResource` will be created. However, any attempts to call the resource endpoint for the audit queue will
fail.

Defining an `audit` queue also auto-wires an `HmppsAuditService` bean for publishing structured audit events — see
[Publishing audit events](#publishing-audit-events-hmppsauditservice) below.

#### FIFO queues and topics

Localstack FIFO (first in, first out) queues and topics can be created by adding the `.fifo` suffix to the queueName
or arn.
[Content Based Deduplication](https://docs.aws.amazon.com/sns/latest/dg/fifo-message-dedup.html) is enabled on
Localstack FIFO topics by default and cannot be disabled.

FIFO Queues can only subscribe to FIFO Topics.

FIFO allows you the option to configure message deduplication and guarantees ordering. There are performance
tradeoffs. [More information on FIFO here](https://docs.aws.amazon.com/sns/latest/dg/sns-fifo-topics.html)

To publish a message to a FIFO topic you must include a `MessageGroupId` — see the `messageGroupId` parameter of
[`HmppsTopic.publish`](#publishing-to-an-sns-topic-hmppstopicpublish).

#### HmppsSqsProperties Definitions

##### :warning: queueId and topicId Must Be All Lowercase And Alpha

As we define the production queue and topic properties in environment variables that map to a complex object in
`HmppsSqsProperties`, Spring is unable to handle a mixed case `queueId` or `topicId` and struggles with hyphens and
underscores. Therefore please make the `queueId` and `topicId` a single word that is all lower case (or upper case
when defining env vars in the Helm values files).

E.g. I know you'd like to use property `hmpps.sqs.queues.my-service-queue.queueName`, but your life will be much
easier if you name the property `hmpps.sqs.queues.myservicequeue.queueName`.

Note that the library's own validation (`HmppsSqsProperties`) only actively rejects a `queueId`/`topicId` that isn't
lowercase — it doesn't reject hyphens or underscores outright. However, using them is still a real source of
confusing failures in practice (particularly once the corresponding `HMPPS_SQS_QUEUES_<queueId>_...` environment
variable names are derived from it), so please stick to lowercase-alpha-only regardless.

| Property                      | Default                 | Description                                                                                                                                                                                                                             |
|-------------------------------|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| provider                      | `aws`                    | `aws` for production or `localstack` for running locally / integration tests.                                                                                                                                                           |
| region                        | `eu-west-2`              | The AWS region where the queues live.                                                                                                                                                                                                   |
| localstackUrl                 | `http://localhost:4566`  | Only used for `provider=localstack`. The location of the running LocalStack instance.                                                                                                                                                   |
| queues                        |                          | A map of `queueId` to `QueueConfig`. One entry is required for each queue. In production these are derived from environment variables with the prefix `HMPPS_SQS_QUEUES_` that should be populated from Kubernetes secrets (see below). |
| topics                        |                          | A map of `topicId` to `TopicConfig`. One entry is required for each topic. In production these are derived from environment variables with the prefix `HMPPS_SQS_TOPICS_` that should be populated from Kubernetes secrets (see below). |
| useWebToken                   | `true`                   | Assumes you will be using Web Identity Token credentials and thus won't need any queue / topic access keys or secrets. Default from 3.0 is to be `true`, only needs to be `false` if running outside of CloudPlatform AWS clusters.     |
| defaultErrorVisibilityTimeout | 0                        | A comma separated list of the time in seconds before a failed message is retried. Applies to all message unless overridden at the queue or event level.                                                                                 |

Each queue declared in the `queues` map is defined in the `QueueConfig` property class

| Property                    | Default | Description                                                                                                                                                                                                                                                                                                                                |
|-----------------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| queueId                     |         | The key to the `queues` map. A unique name for the queue configuration, used heavily when automatically creating Spring beans. Must be lower case letters only (no hyphens or underscores).                                                                                                                                                |
| queueName                   |         | The name of the queue as recognised by AWS or LocalStack. The AWS queue name, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_QUEUE_NAME`.                                                                                                                                                            |
| subscribeTopicId            |         | Only used for `provider=localstack`. The `topicId` of the topic this queue subscribes to when either running integration tests or running locally.                                                                                                                                                                                         |
| subscribeFilter             |         | Only used for `provider=localstack`. The filter policy to be applied when subscribing to the topic. Generally used to filter out certain messages. See your queue's `filter_policy` in `cloud-platform-environments` for an example.                                                                                                       |
| dlqName                     |         | The name of the queue's dead letter queue (DLQ) as recognised by AWS or LocalStack. The AWS queue name of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_DLQ_NAME`. Omit this (along with the other `dlq*` properties below) entirely if the queue doesn't need a DLQ — it's optional.      |
| dlqMaxReceiveCount          | 5       | Only used for `provider=localstack`. Change the number of retries automatically provided by Localstack on DLQs. e.g. It can be useful to change this to 1 when testing DLQ retry functionality.                                                                                                                                            |
| visibilityTimeout           | 30      | Only used for `provider=localstack`. Sets the maximum amount of time (in seconds) that a message is considered to be in process before it is then acknowledged or made visible again to other listeners. See https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html for more information. |
| errorVisibilityTimeout      |         | A comma separated list of the time in seconds before a failed message is retried. Missing config falls back to the global `defaultErrorVisibilityTimeout`.                                                                                                                                                                                 |
| propagateTracing            | `true`  | Reads distributed tracing headers and propagates them onwards, keeping a link to the original published message in your Application Monitor e.g. Microsoft Log Analytics. See [Disabling Tracing of Messages](#disabling-tracing-of-messages) below.                                                                                      |
| eventErrorVisibilityTimeout |         | A map of eventTypes -> comma separated list of the time in seconds before a failed message is retried. Missing config for an eventType falls back to the queue's `errorVisibilityTimeout`.                                                                                                                                                 |
| queueAccessKeyId            |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS access key ID, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_QUEUE_ACCESS_KEY_ID`.                                                                                         |
| queueSecretAccessKey        |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS secret access key, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_QUEUE_SECRET_ACCESS_KEY`.                                                                                 |
| dlqAccessKeyId              |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS access key ID of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_DLQ_ACCESS_KEY_ID`.                                                                                |
| dlqSecretAccessKey          |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS secret access key of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_DLQ_SECRET_ACCESS_KEY`.                                                                        |

Each topic declared in the `topics` map is defined in the `TopicConfig` property class

| Property         | Default | Description                                                                                                                                                                                 |
|------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| topicId          |         | The key to the `topics` map. A unique name for the topic configuration, used heavily when automatically creating Spring beans. Must be lower case letters only (no hyphens or underscores). |
| arn              |         | The ARN of the topic as recognised by AWS and LocalStack. For `provider=aws` this is a real topic ARN, derived from an environment variable of format `HMPPS_SQS_TOPICS_<topicId>_ARN` mapped in your Helm values file. For `provider=localstack` this is just a test configuration value, e.g. `arn:aws:sns:eu-west-2:000000000000:${random.uuid}`. |
| propagateTracing | `true`  | Writes distributed tracing headers to messages and propagates them onwards, keeping a link to message consumers in your Application Monitor e.g. Microsoft Log Analytics.                   |
| accessKeyId      |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS access key ID, should be derived from an environment variable of format `HMPPS_SQS_TOPICS_<topicId>_ACCESS_KEY_ID`.                                   |
| secretAccessKey  |         | DEPRECATED: use IRSA in your Cloud Platform configuration to control queue access. Only used for `provider=aws`. The AWS secret access key, should be derived from an environment variable of format `HMPPS_SQS_TOPICS_<topicId>_SECRET_ACCESS_KEY`.                           |

### Publishing & Sending Messages

This is normally the first thing you need once your queues and topics are configured — how do you actually get an
event onto them? The library provides three helpers. `HmppsTopic.publish` and `HmppsQueue.sendMessage` share
Spring retry/backoff machinery; `HmppsAuditService.publishEvent` sends directly through the AWS SDK client.

#### Publishing to an SNS topic: `HmppsTopic.publish`

Look up your topic via `HmppsQueueService.findByTopicId(topicId)` and call the `publish` extension function on the
returned `HmppsTopic`:

```kotlin
hmppsDomainTopic.publish(
  eventType = event.eventType,
  event = event.body,
)
```

This automatically adds `eventType` as a message attribute (so consumers and the library's own DLQ/error-visibility
handling can read it), retries the publish on failure, and returns a `PublishResponse` containing the message ID.

By default this makes up to 4 attempts (the initial call plus 3 retries), with exponential backoff starting at 1 second.
If all retries run, the backoff waits total about 7 seconds, excluding request duration. Other scenarios are supported by
overriding the optional parameters:

| Parameter        | Default                                             | Description                                                                                             |
|------------------|------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `eventType`      |                                                        | The type of the event, which clients listen to, e.g. `prisoner.movement.added`.                           |
| `event`          |                                                        | The event data, typically JSON, as a string.                                                              |
| `noTracing`      | `false`                                               | Prevents distributed tracing of this message by adding a `noTracing` message attribute.                   |
| `attributes`     | empty                                                 | A map of additional message attributes. `eventType` is always added automatically.                        |
| `retryPolicy`    | 4 attempts                                            | A Spring `RetryPolicy`. Pass `NeverRetryPolicy()` if the calling code already retries (e.g. an idempotent SQS listener), or a `SimpleRetryPolicy` with more attempts for a batch job that can afford to wait longer. |
| `backOffPolicy`  | exponential, starting at 1s                           | A Spring `BackOffPolicy`, e.g. `FixedBackOffPolicy()` for a constant delay between retries.                |
| `messageGroupId` | `null`                                                 | Required when publishing to a FIFO topic.                                                                 |

See the KDoc on `HmppsTopic.publish` in
[`HmppsTopic.kt`](hmpps-sqs-spring-boot-autoconfigure/src/main/kotlin/uk/gov/justice/hmpps/sqs/HmppsTopic.kt) for a
full set of worked examples covering each of these scenarios.

#### Sending directly to an SQS queue: `HmppsQueue.sendMessage`

If you want to send a message directly to a queue rather than publishing to a topic (for example, a queue with no
topic in front of it), look up the queue via `HmppsQueueService.findByQueueId(queueId)` and call `sendMessage` on the
returned `HmppsQueue`:

```kotlin
hmppsQueue.sendMessage(
  eventType = event.eventType,
  event = event.body,
)
```

This supports the same `noTracing`, `attributes`, `retryPolicy` and `backOffPolicy` parameters as
`HmppsTopic.publish` (see the KDoc on `HmppsQueue.sendMessage` in
[`HmppsQueue.kt`](hmpps-sqs-spring-boot-autoconfigure/src/main/kotlin/uk/gov/justice/hmpps/sqs/HmppsQueue.kt) for
worked examples), plus an optional `delayInSeconds` to delay delivery — useful for avoiding race conditions where a
consumer might otherwise read stale data.

#### Publishing audit events: `HmppsAuditService`

If you define a queue with id `audit` (see [Audit queue](#audit-queue)), Spring Boot automatically wires up an
`HmppsAuditService` bean for publishing structured audit events to it. Both `publishEvent` overloads are `suspend`
functions, so call them from a coroutine (e.g. a `suspend` controller method, or `runBlocking`/a coroutine scope in a
non-suspending caller):

```kotlin
hmppsAuditService.publishEvent(
  what = "PRISONER_UPDATED",
  who = "a-user-name",
  subjectId = "A1234BC",
  subjectType = "PRISONER_ID",
)
```

You can also build and pass an `HmppsAuditEvent` directly via the other `publishEvent(hmppsAuditEvent: HmppsAuditEvent)`
overload if you'd rather construct it yourself.

For what audit is, why we have it, and what each field means, see the
[HMPPS Audit documentation](https://dsdmoj.atlassian.net/wiki/x/bIC2SgE).

#### `eventType` message attribute helpers

`HmppsTopic.publish` and `HmppsQueue.sendMessage` already add the `eventType` message attribute for you. If you're
constructing an SNS `PublishRequest` or SQS `SendMessageRequest` manually (for example because you need a feature
that isn't exposed by `publish`/`sendMessage`), the `eventTypeMessageAttributes` builder extension functions can add
the same attribute for you:

```kotlin
PublishRequest.builder()
  .topicArn(topicArn)
  .message(event)
  .eventTypeMessageAttributes(eventType)
  .build()
```

### Listening to Messages

#### SqsListener

The `@Import(SqsBootstrapConfiguration::class)` annotation is included by this library which bootstraps the parts of
the AWS Cloud Spring library required to register the listeners.

#### SqsMessageListenerContainerFactory

To read from a queue with SQS we need a `SqsMessageListenerContainerFactory` for each queue, which can then be
referenced in the `@SqsListener` annotation.

This library will create a container factory for each queue defined in `HmppsSqsProperties` and save them in proxy
class `HmppsQueueContainerFactoryProxy` with a link from each `queueId` to the relevant container factory.

This means that to get a SQS listener working for each queue in `HmppsSqsProperties` you need to declare your
`@SqsListener` annotation in the following format:

```kotlin
  @SqsListener("<queueId>", factory = "hmppsQueueContainerFactoryProxy")
```

where `<queueId>` is taken from [HmppsSqsProperties Definitions](#hmppssqsproperties-definitions).

An example is available in the `test-app`'s
[listeners](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/templatepackagename/service/MessageListener.kt).

##### Overriding the SqsMessageListenerContainerFactory

If you don't wish to use the `HmppsQueueContainerFactoryProxy` because you want to configure your listener in a
different way then simply create your own `SqsMessageListenerContainerFactory` and reference it on the `@SqsListener`
annotation.

#### Typed message parameters: `SnsMessage`

A message arriving on a queue that's subscribed to an SNS topic is wrapped in an SNS envelope containing `Type`,
`Message`, `MessageId` and `MessageAttributes` fields. There are two common ways to handle this in your
`@SqsListener` method:

* Type the parameter as the library's `SnsMessage` and let Spring Cloud AWS deserialize the envelope for you:

  ```kotlin
  @SqsListener("inboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(message: SnsMessage) {
    val event: HmppsEvent = jsonMapper.readValue(message.message)
    inboundMessageService.handleMessage(event)
  }
  ```

* Type the parameter as `String` and parse the envelope yourself:

  ```kotlin
  @SqsListener("outboundqueue", factory = "hmppsQueueContainerFactoryProxy")
  fun processMessage(rawMessage: String) {
    val snsMessage: SnsMessage = jsonMapper.readValue(rawMessage)
    val event: HmppsEvent = jsonMapper.readValue(snsMessage.message)
    outboundMessageService.handleMessage(event)
  }
  ```

Both patterns are demonstrated side by side in `test-app`'s
[MessageListener.kt](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/templatepackagename/service/MessageListener.kt).

Note that [7.0.0](release-notes/7.x.md) changed the point at which `MessageInterceptor` sees the message (now after
deserialization) — if you're upgrading from an older version and your listener used to receive a raw `String`
regardless of parameter type, check your listener signatures still behave as expected.

### Error Handling & DLQ Retry

#### Error Visibility Timeouts

By default, any message that fails will be retried immediately. Once the queue's `maxReceiveCount` is exhausted the
message will be sent to the dead letter queue (DLQ). It would be better to retry failures for longer before they are
sent to the DLQ.

It's possible to configure custom retry behaviour using the `errorVisibilityTimeout` properties to set the time
between each retry on the main queue.

Note that the number of message deliveries is defined in the Cloud Platform terraform as `maxReceiveCount`. This
includes the initial message delivery - so the number of retries is `maxReceiveCount-1`. If there are more retries
than values defined in the timeout strategy then the last value in the list is re-used. If there are more values
defined in the timeout strategy than retries then the later values will be ignored.

To set a global default timeout strategy you can set the number of seconds between each retry. In this example the
1st retry is after 1 second, the 2nd after 10 seconds, the 3rd after 1 minute and the 4th after 10 minutes. If the
last attempt fails the message will be sent to the DLQ.

```yaml
hmpps.sqs:
  defaultErrorVisibilityTimeout: 1, 10, 60, 600
```

Note that the above strategy would require `maxReceiveCount=5` and the 4th retry would have visibility timeout of
600.

It's possible to override the default and set the timeout strategy for a specific queue:

```yaml
hmpps.sqs:
  queues:
    myqueue:
      errorVisibilityTimeout: 1, 2, 4, 8
```

You can also override the queue and set the timeout strategy for a specific event type:

```yaml
hmpps.sqs:
  queues:
    myqueue:
      eventErrorVisibilityTimeout:
        my.special.event: 0, 1, 2, 3
```

To see how this is working in your application run the following App Insights Log Analytics query:

```KQL
AppTraces
| where AppRoleName == '<your-app>'
| where Message startswith 'Setting error visibility timeout'
```

And to find any messages that were sent to the DLQ try the following:

```KQL
AppTraces
| where AppRoleName == '<your-app>'
| where Message startswith 'Setting error visibility timeout'
| where Message has 'last retry'
```

We also publish a telemetry event when moving a message to the DLQ. To find them for a specific event type:

```KQL
AppEvents
| where Name == '<your-event-type>-sent-to-dlq'
```

Or for a more general search in your application:

```KQL
AppEvents
| where AppRoleName == '<your-app>'
| where Name endswith 'sent-to-dlq'
```

#### Queue Admin Endpoints

When SQS messages fail to be processed by the main queue they are sent to the Dead Letter Queue (DLQ). We then find
ourselves in one of the following scenarios:

* The failure was transient and a retry will allow the message to be processed
* The failure was due to an unrecoverable error and we want to discard the message while we investigate the error and
  fix it

Class `HmppsQueueResource` provides the following endpoints, all under `/queue-admin`:

| Method | Path                            | Description                                                                                                 |
|--------|----------------------------------|---------------------------------------------------------------------------------------------------------------|
| `GET`  | `/get-dlq-messages/{dlqName}`    | Peek at (without removing) up to `maxMessages` (default 100) messages currently on the DLQ.                   |
| `PUT`  | `/retry-dlq/{dlqName}`           | Retry every message currently on the named DLQ (see [How retry actually works](#how-retry-actually-works)).   |
| `PUT`  | `/retry-all-dlqs`                | Retry every DLQ configured in the application.                                                                 |
| `PUT`  | `/purge-queue/{queueName}`       | Purge all messages from the named queue (works for both main queues and DLQs; the `audit` queue is excluded).  |

##### How retry actually works

`retry-dlq`/`retry-all-dlqs` count the messages currently on the DLQ, then start a single native SQS
[message move task](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dlq-message-moving.html)
(`StartMessageMoveTaskRequest`) to move them all back onto the main queue, and return immediately. This means:

* `messagesFoundCount` in the response is a snapshot of how many messages were on the DLQ at the moment the task
  started — it isn't a guarantee of how many were actually moved, and the move happens asynchronously in the
  background (AWS applies its own rate limiting to the move).
* To monitor draining, check the native message move task's status and poll the DLQ with
  `countAllMessagesOnQueue` (see [Observability](#observability)), which includes visible and in-flight messages.
  Queue counts are approximate; a zero visible-message count alone does not confirm that the DLQ is empty.

#### Usage

For transient errors you can use the Kubernetes Cronjob defined in the
[generic service helm chart](https://github.com/ministryofjustice/hmpps-helm-charts/tree/main/charts/generic-service#retrying-messages-on-a-dead-letter-queue)
to automatically retry all DLQ messages. The Cronjob is configured to run every 10 minutes before
[an alert triggers for the age of the DLQ message](https://github.com/ministryofjustice/cloud-platform-environments/blob/main/namespaces/live-1.cloud-platform.service.justice.gov.uk/offender-events-prod/09-prometheus-sqs-sns.yaml#L13).

Unrecoverable errors should be fixed such that they no longer fail and are not sent to the DLQ. In the meantime these
can be removed by purging the DLQ to prevent the alert from firing.

Note that we have an alternative strategy for dealing with transient errors, which is to set longer retry timeouts
using the [Error Visibility Timeout configuration](#error-visibility-timeouts). This is intended to prevent transient
errors from ever landing on the DLQ, thus removing the need for the retry DLQ cronjob.

#### How Do I find The DLQ/Queue Name?

The queue names are generally defined in Kubernetes secrets for the namespace which are then mapped into the Spring
Boot application as configuration properties.

The queue names should also appear on the `/health` page if using this library for queue health.

#### Securing Endpoints

Most endpoints in `HmppsQueueResource` will have a default role required to access them which is overridable by a
configuration property found in `hmpps.sqs.queueAdminRole`.

Note that any endpoints defined in `HmppsQueueResource` that are not secured by a role are only intended for use
within the Kubernetes namespace and must not be left wide open - instead they should be secured in the Kubernetes
ingress. See the
[example ingress](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/example/ingress.yaml)
for how to block the endpoints from outside the namespace.

By default, the `/retry-all-dlqs` endpoint is unsecured. This is because it was originally only intended to be used
by the Kubernetes Cronjob. However, it can be secured by setting the property `hmpps.sqs.protectRetryAll` and it will
then behave like all the other endpoints.

#### Open API Docs

We do not provide any detailed Open API documentation for these endpoints. This is because there is a variety of
Open API document generators being used at different versions and catering for them all would require a complicated
solution for little benefit.

Hopefully your Open API document generator can find the endpoints automatically and includes them in the Open API
docs. If not you may have to introduce some configuration to point the generator at the endpoints, for example using
the Springfox
[ApiSelectorBuilder#apis method](https://springfox.github.io/springfox/docs/snapshot/#springfox-spring-mvc-and-spring-boot)
to add the base package `uk.gov.justice.hmpps.sqs`.

#### Reactive Queue Admin Endpoints

If you're building an application with Reactive endpoints then your ResourceServer or Node app will be configured to
support Reactive.

The library will automatically switch to the reactive Queue admin endpoints if it detects a running a reactive
application (`@ConditionalOnWebApplication(type = REACTIVE)`). Note that this will disable the non-reactive endpoints
(which are enabled by default).

### Observability

#### Distributed Tracing of Messages

If `propagateTracing` is set to `true` (the default) then a new
[Span](https://opentelemetry.io/docs/concepts/signals/traces/#spans) is created whenever:

1. A message is published. The span is named as `PUBLISH <event type>` where `<event type>` is obtained from the
   `eventType` field in the message attributes. If the event type can't be found then it will simply be named
   `PUBLISH`. These spans can then be viewed in the `AppDependencies` in Log Analytics.
2. A message is received. The span is named as `RECEIVE <event type>` where `<event type>` is obtained from the
   `eventType` field in the message attributes. If the event type can't be found then it will simply be named
   `RECEIVE`. These spans can then be viewed in the `AppRequests` in Log Analytics.

`HmppsTopic.publish` and `HmppsQueue.sendMessage` (see [Publishing & Sending Messages](#publishing--sending-messages))
add the `eventType` message attribute for you automatically. If you're constructing a publish/send request manually
there are `eventTypeMessageAttributes` builder extension functions in the library to help add it yourself.

To investigate the results of a published message obtain the `OperationId` for the message, then:

```
AppRequests
| union AppDependencies
| where OperationId == "<operation id>"
```

will show all the messages that were then received and published from that original message. Alternatively going to
Transaction Search in Log Analytics will then show a graph with that information.

#### Disabling Tracing of Messages

if `propagateTracing` is set to `false` the new spans will still be created, but they will not be linked to the
original message by `OperationId`.

#### Queue Health

All queues should be included on an application's health page. An unhealthy queue indicates an unhealthy service.

For each queue defined in `HmppsSqsProperties` we create a `HmppsQueueHealth` bean.

The Spring beans produced have names of format `<queueId>-health`. If you wish to override the automatically created
bean then provide a custom bean with the same name. Upon finding the custom bean this library will use the custom
bean rather than generating one.

##### Testing Queue Health

Unit tests for the generic queue health exist in this library so there is no need to add more.

You should however create a couple of integration tests for your queue health in case your implementation has
problems. Examples are available in the `test-app` - see classes:

* happy path - `QueueHealthCheckTest`
* negative path - `QueueHealthCheckNegativeTest`

#### Topic Health

All topics should be included on an application's health page.

For each topic defined in `HmppsSqsProperties` we create a `HmppsQueueHealth` bean.

The Spring beans produced have names of format `<topicId>-health`. If you wish to override the automatically created
bean then provide a custom bean with the same name. Upon finding the custom bean this library will use the custom
bean rather than generating one.

##### Testing Topic Health

Unit tests for the generic topic health exist in this library so there is no need to add more.

You should however create a couple of integration tests for your topic health in case your implementation has
problems. Examples are available in the `test-app` - see classes:

* happy path - `TopicHealthCheckTest`
* negative path - `TopicHealthCheckNegativeTest`

#### Message count utilities

Two extension functions on `SqsAsyncClient` are available if you need to check queue depth from your own code (e.g.
a test assertion waiting for a queue to empty, or a custom metric):

* `SqsAsyncClient.countMessagesOnQueue(queueUrl)` — the approximate number of **visible** messages on the queue.
* `SqsAsyncClient.countAllMessagesOnQueue(queueUrl)` — the approximate number of visible **and** in-flight
  (received-but-not-yet-acknowledged) messages.

Both return a `CompletableFuture<Int>`. See the KDoc in
[`HmppsQueueService.kt`](hmpps-sqs-spring-boot-autoconfigure/src/main/kotlin/uk/gov/justice/hmpps/sqs/HmppsQueueService.kt)
for more detail on the distinction between the two.

### SQS Beans

As each queue and dead letter queue (DLQ) has its own access key and secret we create an SQS client for each one.
Historically this has been done in Spring `@Configuration` classes for both AWS and LocalStack (for testing) but this
becomes complicated and hard to follow when there are multiple queues and DLQs.

To remove this pain each queue defined in `HmppsSqsProperties` should have an `SqsAsyncClient` created for both the main
queue and the associated DLQ.

The bean names have the following format and can be used with `@Qualifier` to inject the beans into another
`@Component`:

* main queue - `<queueId>-sqs-client`
* DLQ - `<queueId>-sqs-dlq-client`

#### LocalStack SQS Beans

In the past we would generally have a shell script to create any queues in a running LocalStack instance so that we
can run tests against them.

This library will now create the queues automatically when the provider is LocalStack so we don't need the queue
creation shell script.

#### Overriding SQS Beans

If for any reason you don't want to use the `SqsAsyncClient` beans automatically created by this library but still want
other features such as a `HealthIndicator` or queue admin endpoints then it's possible to override them.

At the point this library attempts to generate any bean and register with the `ApplicationContext`, if it finds an
existing bean with the same name then it does nothing and uses the existing bean.

So first find the bean names you wish to override as mentioned in [SQS Beans](#sqs-beans). Then create
your own `SqsAsyncClient` bean with the same name.

### AmazonSNS Beans

As each topic has its own access key and secret we create an Amazon SNS client for each one. Historically this has
been done in Spring `@Configuration` classes for both AWS and LocalStack (for testing) but this becomes complicated
and hard to follow.

To remove this pain each topic defined in `HmppsSqsProperties` should have an `AmazonSNS` created.

The bean names have the format `<topicId>-sns-client` and can be used with `@Qualifier` to inject the beans into
another `@Component`:

#### LocalStack AmazonSNS Beans

In the past we would generally have a shell script to create any topics in a running LocalStack instance so that we
can run tests against them. The same goes for queues subscribing to the topics.

This library will now create the topics automatically and subscribe queues to them when `provider=localstack` so we
don't need the shell script.

### Testing

#### Random Queue and Topic Names

If you look in the `test-app`'s
[application properties](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml)
you can see that it uses random queue and topic names. These are only needed for integration testing.

When `provider=localstack` the queues/topics are created in LocalStack as soon as the `SqsAsyncClient`/`SnsAsyncClient` beans
are created. By using random names we can ensure that if Spring loads a new context during integration testing then
the new context gets new queues/topics which cannot interfere with tests from another context.

If you need to know the actual queue/topic names used you can find them in the Spring logs. You can also see them in
LocalStack with the commands:

```
AWS_ACCESS_KEY_ID=foobar AWS_SECRET_ACCESS_KEY=foobar aws --endpoint-url=http://localhost:4566 --region=eu-west-2 sqs list-queues
AWS_ACCESS_KEY_ID=foobar AWS_SECRET_ACCESS_KEY=foobar aws --endpoint-url=http://localhost:4566 --region=eu-west-2 sns list-topics
```

#### SpyBeans

It is only possible to use the `@SpyBean` annotation for beans declared in a `@Configuration` class (unless you do
some hacking with the Spring lifecycle support). As the library creates these beans on an ad hoc basis it is not
possible to create spies for them.

However, as mentioned above you can override the automatically generated beans with your own bean, e.g. with bean
name `<queueId>-sqs-client` or `<queueId>-sqs-dlq-client`. If you do this in a `@TestConfiguration` using the `@Bean`
annotation then it is possible to declare a corresponding SpyBean. `HmppsQueueFactory` provides factory methods to
assist in creating such beans.

This is complicated so first check the usage of your Spy Beans. Are they actually being used to mock or verify or
just using them to purge queues / count messages on a queue? If there is no mocking or verifying then you can get the
real bean from the `HmppsQueueService` by doing:

```kotlin
  hmppsQueueService.findByQueueId("<insert queue id here>")?.sqsClient
  ?: throw IllegalStateException("Unable to retrieve an SQS client for HmppsQueue with id <insert queue id here>")
```

If you definitely need a SpyBean then there is an example in the `test-app` which defines beans to spy on in a
`@TestConfiguration`. See
[IntegrationTestBase](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/kotlin/uk/gov/justice/digital/hmpps/templatepackagename/integration/IntegrationTestBase.kt).

#### MockBeans

MockBeans have no benefit over SpyBeans but they cause Spring to reload the application with a fresh context slowing
down your tests. They probably work with this library like SpyBeans do but this hasn't been tested.

Consider using a SpyBean instead and declaring it in the base IntegrationTest class which then allows you to mock
and/or verify or neither depending upon the requirements of each test.

#### Testcontainers

In the past many queueing applications have allowed running against either a Testcontainers LocalStack instance or a
standalone LocalStack instance started manually with docker compose (which is required when running the tests in
CI).

This led to some applications having a very complicated configuration with 3 sets of `SqsAsyncClient` beans required -
production, standalone LocalStack and Testcontainers LocalStack.

When using this library there is an easier way to use Testcontainers. Look in the `test-app` at class
`IntegrationTestBase` in the `companion object`. There is an example of how to start a Testcontainers LocalStack
instance only if a standalone LocalStack instance is not already running. This means that if you check out the
library and run the tests then Testcontainers will jump in and start a LocalStack instance for you. However, if you
are developing the application and would prefer not to wait for the Testcontainers LocalStack instance to start and
stop on every test run then you can start a standalone LocalStack instance and the tests will use that.

## Modules

We are using a multi-module project in order to create functional tests that use the imported library.

### hmpps-sqs-spring-boot-autoconfigure

This is the module that generates the autoconfigure library for consuming in the starter library. It provides all of
the functionality provided by this project.

### hmpps-sqs-spring-boot-starter

This is the module that generates the starter library for publishing. The starter library includes the autoconfigure
library and any dependencies required to make it work.

#### Running Tests in your own project without LocalStack dependency

There may be scenarios where you want to run SpringBoot tests in your own project, but you don't want all the
autoconfigured beans this library would bring in — for instance you might want to test a portion of your application
that does not depend on queues being present, so you don't have the overhead of starting localstack. This can be
achieved by disabling the HmppsSqsConfiguration autoconfigure bean, one way to do this would be:

```kotlin
@SpringBootTest(
  webEnvironment = RANDOM_PORT,
  properties = ["spring.autoconfigure.exclude=uk.gov.justice.hmpps.sqs.HmppsSqsConfiguration"],
)
class MyTest {
}
```

### test-app

This module contains a copy of the [Kotlin template project](https://github.com/ministryofjustice/hmpps-template-kotlin)
with the library included as a dependency. This means there is a lot of stuff in the `test-app` that isn't needed for
the tests, such as the CI config - these have been left on purpose so that it is easier to compare the test app with
the template project when attempting to keep the test app up to date.

Various queue related functionality has been added to the template project so that we can run tests against the
library.

Note that this module does not produce an artifact for publishing - we only publish the library from the
`hmpps-sqs-spring-boot-starter` module.

#### test-app-reactive

This is a copy of the `test-app` which uses a Reactive Spring configuration, used to prove out the library's reactive
support (e.g. the reactive queue admin endpoints). It was deliberately kept as a thinner example rather than a full
mirror of `test-app` — not every listener/service example in `test-app` has a reactive counterpart, and that's fine;
it doesn't need full feature parity to do its job. All instructions relating to the test-app apply to
test-app-reactive too, for the functionality it does cover.

## Contributing

See [CONTRIBUTING.md](readme-docs/CONTRIBUTING.md).

## Publishing

See [PUBLISHING.md](readme-docs/PUBLISHING.md).
