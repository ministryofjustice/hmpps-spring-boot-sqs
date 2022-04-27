# HMPPS Spring Boot SQS / SNS

A Spring Boot starter library providing utilities for using Amazon Simple Queue Service (SQS) and Simple Notification Service (SNS). The library is very opinionated towards usage within HMPPS, e.g. we assume that each queue has its own secrets rather than sharing access between queues.

## Overview

We have many services that use AWS SQS queues and topics with various patterns for managing queues that have evolved over time. These patterns have been duplicated widely and thus are subject to the usual problems associated with a lack of DRY such as code drift and the proliferation of boilerplate code.

This library is intended to capture the most common patterns and make them easy to distribute among other projects. The goal is to provide various queue management and configuration tasks out of the box.

The library relies on [Spring Boot Auto-configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.auto-configuration) based upon [configuration properties](#hmpps-queue-properties).

## Release Notes

##### [1.1.4](release-notes/1.1.4.md)
##### [1.1.3](release-notes/1.1.3.md)
##### [1.1.2](release-notes/1.1.2.md)
##### [1.1.1](release-notes/1.1.1.md)
##### [1.1.0](release-notes/1.1.0.md)
##### [1.0.5](release-notes/1.0.5.md)
##### [1.0.4](release-notes/1.0.4.md)
##### [1.0.3](release-notes/1.0.3.md)
##### [1.0.2](release-notes/1.0.2.md)
##### [1.0.1](release-notes/1.0.1.md)
##### [1.0.0](release-notes/1.0.0.md)

## How To Use This Library

Find the latest published version of the library by searching on Maven Central for `hmpps-spring-boot-sqs`.  (If you can't find the version mentioned in `/lib/build.gradle.kts` please be patient, it can take a while to publish to Maven Central).

Add the following dependency to your Gradle build script:

*Kotlin*

``` kotlin
implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:<library-version>")
```

*Groovy*

``` groovy
implementation 'uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:<library-version>'
```

Then create some properties defining the queue(s) in the application. See [HMPPS Queue Properties](#hmpps-queue-properties) for information on the properties, and check the [test-app](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/tree/main/test-app) for an example.

You will now get features like HealthIndicators, queue admin endpoints, AmazonSQS/SNS beans and a custom JmsListenerContainerFactory - all for free. Read more about all available features [here](#features).

## How To Run This Locally

A test application found in module `test-app` applies this library and is used for both functional tests and as an example application you can spin up.

See [Running All Tests](#running-all-tests) and [Running the test-app](#running-the-test-app) for more details.

## Features

### HMPPS Queue Properties

This library is driven by some configuration properties prefixed `hmpps.sqs` that are loaded into class `HmppsSqsProperties`. Based on the properties defined the library will attempt to:

* create `AmazonSQS` beans for each queue defined which are configured for AWS (or LocalStack for testing / running locally)
* create `AmazonSNS` beans for each topic defined which are configured for AWS (or LocalStack for testing / running locally)
* create a `HealthIndicator` for each queue and topic which is registered with Spring Boot Actuator and appears on your `/health` page
* add `HmpspQueueResource` to the project which provides endpoints for retrying DLQ messages and purging queues
* create a JMS listener connection factory for each queue defined
* create LocalStack queues and topics for testing against, and subscribe queues to topics where configured

Examples of property usage can be found in the test project in the following places:

* Production: [test-app/.../values.yaml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/values.yaml#L33)
* Running locally with LocalStack: [test-app/.../application-localstack.yml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/resources/application-localstack.yml)
* Integration Test: [test-app/.../application-test.yml#L8](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml#L8)

#### HmppsSqsProperties Definitions

| Property | Default | Description |
| -------- | ------- | ----------- |
| provider | `aws` | `aws` for production or `localstack` for running locally / integration tests.
| region   | `eu-west-2` | The AWS region where the queues live. |
| localstackUrl | `http://localhost:4566` | Only used for `provider=localstack`. The location of the running LocalStack instance. |
| queues   | | A map of `queueId` to `QueueConfig`. One entry is required for each queue. In production these are derived from environment variables with the prefix `HMPPS_SQS_QUEUES_` that should be populated from Kubernetes secrets (see below).
| topics   | | A map of `topicId` to `TopicConfig`. One entry is required for each topic. In production these are derived from environment variables with the prefix `HMPPS_SQS_TOPICS_` that should be populated from Kubernetes secrets (see below).

Each queue declared in the `queues` map is defined in the `QueueConfig` property class

| Property | Default | Description |
| -------- | ------- | ----------- |
| queueId | | The key to the `queues` map. A unique name for the queue configuration, used heavily when automatically creating Spring beans. Must be lower case. |
| queueName | | The name of the queue as recognised by AWS or LocalStack. |
| queueAccessKeyId | | Only used for `provider=aws`. The AWS access key ID, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_QUEUE_ACCESS_KEY_ID`. |
| queueSecretAccessKey | | Only used for `provider=aws`. The AWS secret access key, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_QUEUE_SECRET_ACCESS_KEY`. |
| subscribeTopicId | | Only used for `provider=localstack`. The `topicId` of the topic this queue subscribes to when either running integration tests or running locally. |
| subscribeFilter | | Only used for `provider=localstack`. The filter policy to be applied when subscribing to the topic. Generally used to filter out certain messages. See your queue's `filter_policy` in `cloud-platform-environments` for an example. |
| asyncQueueClient | `false` | If true then the `AmazonSQS` bean created will be an `AmazonSQSAsync` instance. |
| dlqName | | The name of the queue's dead letter queue (DLQ) as recognised by AWS or LocalStack. |
| dlqAccessKeyId | | Only used for `provider=aws`. The AWS access key ID of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_DLQ_ACCESS_KEY_ID`. |
| dlqSecretAccessKey | | Only used for `provider=aws`. The AWS secret access key of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUES_<queueId>_DLQ_SECRET_ACCESS_KEY`. |
| asyncDlqClient | `false` | If true then the `AmazonSQS` bean created will be an `AmazonSQSAsync` instance. |

Each topic declared in the `topics` map is defined in the `TopicConfig` property class

| Property | Default | Description |
| -------- | ------- | ----------- |
| topicId | | The key to the `topics` map. A unique name for the topic configuration, used heavily when automatically creating Spring beans. Must be lower case. |
| arn | | The ARN of the topic as recognised by AWS and LocalStack. |
| accessKeyId | | Only used for `provider=aws`. The AWS access key ID, should be derived from an environment variable of format `HMPPS_SQS_TOPICS_<topicId>_ACCESS_KEY_ID`. | 
| secretAccessKey | | Only used for `provider=aws`. The AWS secret access key, should be derived from an environment variable of format `HMPPS_SQS_TOPICS_<topicId>_SECRET_ACCESS_KEY`. |
| asyncClient | `false` | If true then the `AmazonSNS` bean created will be an `AmazonSNSAsync` instance. |

#### :warning: queueId and topicId Must Be All Lowercase

As we define the production queue and topic properties in environment variables that map to a complex object in `HmppsSqsProperties` Spring is unable to handle a mixed case `queueId` or `topicId`.

### JmsListener

The `@EnableJms` annotation is included by this library.

### JmsListenerContainerFactory

To read from a queue with JMS we need a `JmsListenerContainerFactory` for each queue which can then be referenced in the `@JmsListener` annotation.

This library will create a container factory for each queue defined in `HmppsSqsProperties` and save them in proxy class `HmppsQueueContainerFactoryProxy` with a link from each `queueId` to the relevant container factory.

This means that to get a JMS listener working for each queue in `HmppsSqsProperties` you need to declare your `@JmsListener` annotation in the following format:

```kotlin
  @JmsListener(destination = "<queueId>", containerFactory = "hmppsQueueContainerFactoryProxy")
```

where `<queueId>` is taken from [HmppsSqsProperties Definitions](#hmppssqsproperties-definitions)

An example is available in the `test-app`'s [listeners](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/service/MessageListener.kt).

#### Overriding the JmsListenerContainerFactory

If you don't wish to use the `HmppsQueueContainerFactoryProxy` because you want to configure your listener in a different way then simply create your own `DefaultJmsListenerContainerFactory` and reference it on the `@JmsListener` annotation.

### AmazonSQS Beans

As each queue and dead letter queue (DLQ) has its own access key and secret we create an SQS client for each one. Historically this has been done in Spring `@Configuration` classes for both AWS and LocalStack (for testing) but this becomes complicated and hard to follow when there are multiple queues and DLQs.

To remove this pain each queue defined in `HmppsSqsProperties` should have an `AmazonSQS` created for both the main queue and the associated DLQ.

The bean names have the following format and can be used with `@Qualifier` to inject the beans into another `@Component`:

* main queue - `<queueId>-sqs-client`
* DLQ - `<queueId>-sqs-dlq-client`

#### LocalStack AmazonSQS Beans

In the past we would generally have a shell script to create any queues in a running LocalStack instance so that we can run tests against them.

This library will now create the queues automatically when the provider is LocalStack so we don't need the queue creation shell script.

#### Overriding AmazonSQS Beans

If for any reason you don't want to use the `AmazonSQS` beans automatically created by this library but still want other features such as a `HealthIndicator` or queue admin endpoints then it's possible to override them.

At the point this library attempts to generate any bean and register with the `ApplicationContext`, if it finds an existing bean with the same name then it does nothing and uses the existing bean.

So first find the bean names you wish to override as mentioned in [AmazonSQS Beans](#amazonsqs-beans). Then create your own `AmazonSQS` bean with the same name.

#### Optional DLQ

Queues without dead letter queues are supported by this library.  Configure the queue in the same manner as a normal queue, but omit any dlq properties.

#### SpyBeans

It is only possible to use the `@SpyBean` annotation for beans declared in a `@Configuration` class (unless you do some hacking with the Spring lifecycle support). As the library creates these beans on an ad hoc basis it is not possible to create spies for them.

However, as mentioned above you can override the automatically generated beans with your own bean, e.g. with bean name `<queueId>-sqs-client` or `<queueId>-sqs-dlq-client`. If you do this in a `@TestConfiguration` using the `@Bean` annotation then it is possible to declare a corresponding SpyBean. `HmppsQueueFactory` provides factory methods to assist in creating such beans.

This is complicated so first check the usage of your Spy Beans. Are they actually being used to mock or verify or just using them to purge queues / count messages on a queue? If there is no mocking or verifying then you can get the real bean from the `HmppsQueueService` by doing:

```kotlin
  hmppsQueueService.findByQueueId("<insert queue id here>")?.sqsClient ?: throw IllegalStateException("Unable to retrieve an SQS client for HmppsQueue with id <insert queue id here>")
```

If you definitely need a SpyBean then there is an example in the `test-app` which defines beans to spy on in a `@TestConfiguration`. See [IntegrationTestBase](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/integration/IntegrationTestBase.kt).

#### MockBeans

MockBeans have no benefit over SpyBeans but they cause Spring to reload the application with a fresh context slowing down your tests. They probably work with this library like SpyBeans do but this hasn't been tested.

Consider using a SpyBean instead and declaring it in the base IntegrationTest class which then allows you to mock and/or verify or neither depending upon the requirements of each test.

#### Random Queue Names

If you look in the `test-app`'s [application properties](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml) you can see that it uses random queue names.

When `provider=localstack` the queues are created in LocalStack as soon as the `AmazonSQS` beans are created. By using random queue names we can ensure that if Spring loads a new context during integration testing then the new context gets new queues which cannot interfere with tests from another context.

If you need to know the actual queue names used you can find them in the Spring logs. You can also see them in LocalStack with command:

`AWS_ACCESS_KEY_ID=foobar AWS_SECRET_ACCESS_KEY=foobar aws --endpoint-url=http://localhost:4566 --region=eu-west-2 sqs list-queues`

### AmazonSNS Beans

As each topic has its own access key and secret we create an Amazon SNS client for each one. Historically this has been done in Spring `@Configuration` classes for both AWS and LocalStack (for testing) but this becomes complicated and hard to follow.

To remove this pain each topic defined in `HmppsSqsProperties` should have an `AmazonSNS` created.

The bean names have the format `<topicId>-sns-client` and can be used with `@Qualifier` to inject the beans into another `@Component`:

#### LocalStack AmazonSNS Beans

In the past we would generally have a shell script to create any topics in a running LocalStack instance so that we can run tests against them. The same goes for queues subscribing to the topics.

This library will now create the topics automatically and subscribe queues to them when `provider=localstack` so we don't need the shell script.

#### Random Topic Names

If you look in the `test-app`'s [application properties](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml) you can see that it uses random topic names.

When `provider=localstack` the topics are created in LocalStack as soon as the `AmazonSNS` beans are created. By using random topic names we can ensure tests do not interfere with each other.

If you need to know the actual topic names used you can find them in the Spring logs. You can also see them in LocalStack with command:

`AWS_ACCESS_KEY_ID=foobar AWS_SECRET_ACCESS_KEY=foobar aws --endpoint-url=http://localhost:4566 --region=eu-west-2 sns list-topics`

### Queue Health

All queues should be included on an application's health page. An unhealthy queue indicates an unhealthy service.

For each queue defined in `HmppsSqsProperties` we create a `HmppsQueueHealth` bean.

The Spring beans produced have names of format `<queueId>-health`. If you wish to override the automatically created bean then provide a custom bean with the same name. Upon finding the custom bean this library will use the custom bean rather than generating one.

#### Testing Queue Health

Unit tests for the generic queue health exist in this library so there is no need to add more.

You should however create a couple of integration tests for your queue health in case your implementation has problems. Examples are available in the `test-app` - see classes:

* happy path - `QueueHealthCheckTest`
* negative path - `QueueHealthCheckNegativeTest`

### Topic Health

All topics should be included on an application's health page.

For each topic defined in `HmppsSqsProperties` we create a `HmppsQueueHealth` bean.

The Spring beans produced have names of format `<topicId>-health`. If you wish to override the automatically created bean then provide a custom bean with the same name. Upon finding the custom bean this library will use the custom bean rather than generating one.

#### Testing Topic Health

Unit tests for the generic topic health exist in this library so there is no need to add more.

You should however create a couple of integration tests for your topic health in case your implementation has problems. Examples are available in the `test-app` - see classes:

* happy path - `TopicHealthCheckTest`
* negative path - `TopicHealthCheckNegativeTest`

### Queue Admin Endpoints

When SQS messages fail to be processed by the main queue they are sent to the Dead Letter Queue (DLQ). We then find ourselves in one of the following scenarios:

* The failure was transient and a retry will allow the message to be processed
* The failure was due to an unrecoverable error and we want to discard the message while we investigate the error and fix it

Class `HmppsQueueResource` provides endpoints to retry and purge messages on a DLQ.

#### Usage

For transient errors we would typically create a Kubernetes Cronjob to automatically retry all DLQ messages. The Cronjob should be configured to run before [an alert triggers for the age of the DLQ message](https://github.com/ministryofjustice/cloud-platform-environments/blob/main/namespaces/live-1.cloud-platform.service.justice.gov.uk/offender-events-prod/09-prometheus-sqs-sns.yaml#L13) - typically every 10 minutes. See the [example Cronjob](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml) for more details.

Unrecoverable errors should be fixed such that they no longer fail and are not sent to the DLQ. In the meantime these can be removed by purging the DLQ to prevent the alert from firing.

#### How Do I find The DLQ/Queue Name?

The queue names are generally defined in Kubernetes secrets for the namespace which are then mapped into the Spring Boot application as configuration properties.

The queue names should also appear on the `/health` page if using this library for queue health.

#### Securing Endpoints

Most endpoints in `HmppsQueueResource` will have a default role required to access them which is overridable by a configuration property found in `hmpps.sqs.queueAdminRole`.

Note that any endpoints defined in `HmppsQueueResource` that are not secured by a role are only intended for use within the Kubernetes namespace and must not be left wide open - instead they should be secured in the Kubernetes ingress. See the [example ingress](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml) for how to block the endpoints from outside the namespace.

#### Open API Docs

We do not provide any detailed Open API documentation for these endpoints. This is because there is a variety of Open API document generators being used at different versions and catering for them all would require a complicated solution for little benefit.

Hopefully your Open API document generator can find the endpoints automatically and includes them in the Open API docs. If not you may have to introduce some configuration to point the generator at the endpoints, for example using the Springfox [ApiSelectorBuilder#apis method](https://springfox.github.io/springfox/docs/snapshot/#springfox-spring-mvc-and-spring-boot) to add the base package `uk.gov.justice.hmpps.sqs`.

### Testcontainers

In the past many queueing applications have allowed running against either a Testcontainers LocalStack instance or a standalone LocalStack instance started manually with docker-compose (which is required when running the tests on CircleCI).

This led to some applications having a very complicated configuration with 3 sets of `AmazonSQS` beans required - production, standalone LocalStack and Testcontainers LocalStack.

When using this library there is an easier way to use Testcontainers. Look in the `test-app` at class `IntegrationTestBase` in the `companion object`. There is an example of how to start a Testcontainers LocalStack instance only if a standalone LocalStack instance is not already running. This means that if you check out the library and run the tests then Testcontainers will jump in and start a LocalStack instance for you. However, if you are developing the application and would prefer not to wait for the Testcontainers LocalStack instance to start and stop on every test run then you can start a standalone LocalStack instance and the tests will use that.

## Modules

We are using a multi-module project in order to create functional tests that use the imported library.

### hmpps-sqs-spring-boot-autoconfigure

This is the module that generates the autoconfigure library for consuming in the starter library. It provides all of the functionality provided by this project.

### hmpps-sqs-spring-boot-starter

This is the module that generates the starter library for publishing. The starter library includes the autoconfigure library and any dependencies required to make it work.

#### Running the Unit Tests

To run only the unit tests found in the `hmpps-sqs-spring-boot-autoconfigure` module use command:

`./gradlew hmpps-sqs-spring-boot-autoconfigure:test`

#### Running All Tests

Use the following command to run all tests, with the functional tests running against a Testcontainers LocalStack instance:

`./gradlew test`

Note that Testcontainers only starts if LocalStack is not running on port 4566.

#### Running Tests in your own project without LocalStack dependency

There maybe scenarios where you want to run SpringBoot tests in your own project, but you don't want all the autoconfigured beans
this library would bring in, for instance you might want to test a portion of your application that does not depend on queues being present, so you don't 
have the overhead of starting localstack. This can be achieved by disabling the HmppsSqsConfiguration autoconfigure bean, one way to do this would be

```
@SpringBootTest(
webEnvironment = RANDOM_PORT,
properties =
["spring.autoconfigure.exclude=uk.gov.justice.hmpps.sqs.HmppsSqsConfiguration"]
)
class MyTest {
}
```
##### With Standalone LocalStack

If you are developing in this library then starting and stopping Testcontainers LocalStack for every test run quickly becomes tedious.

Use this command to run a standalone LocalStack instance:

`docker-compose -f docker-compose-test.yml up localstack`

And to run the tests use this command - as often as you like:

`./gradlew test`

The standalone LocalStack instance does not need stopping and starting between test runs.

### test-app

This module contains a copy of the [Kotlin template project](https://github.com/ministryofjustice/hmpps-template-kotlin) with the library included as a dependency. This means there is a lot of stuff in the `test-app` that isn't needed for the tests, such as the Circle config.yml - these have been left on purpose so that it is easier to compare the test app with the template project when attempting to keep the test app up to date.

Various queue related functionality has been added to the template project so that we can run tests against the library.

Note that this module does not produce an artifact for publishing - we only publish the library from the `lib` module.

#### Running the Functional Tests

From the root of the project run the following command to test only the test-app tests against a Testcontainers LocalStack instance:

`./gradlew test-app:test`

##### With Standalone LocalStack

If you are developing in this library then starting and stopping a Testcontainers LocalStack instance for every test run quickly becomes tedious.

Use this command to start a standalone LocalStack instance:

`docker-compose -f docker-compose-test.yml up localstack`

From the root of the project run the following command to test only the test-app tests:

`./gradlew test-app:test`

The standalone LocalStack instance does not need stopping and starting between test runs.

### Running the test-app

Running the `test-app` locally can be useful for debugging features provided by this library.

Start localstack with command:

`docker-compose -f docker-compose-test.yml up localstack`

Then run the `test-app` in your IDE from main class `HmppsTemplateKotlin` using Spring profiles `localstack` and `stdout`.

Some messages to process can be found in `test-app/src/test/resources/test-messages`. These can be sent to the inbound topic with the following commands:

```bash
bash $(find -name offender-movement-reception.sh)
bash $(find -name offender-movement-discharge.sh)
```

## How To Contribute To This Library

Raise a PR and ask for a review in the MOJDT Slack channel `#dps_dev`.

If accepted make sure that the version number in `lib/build.gradle.kts` has been upgraded according to [Semver rules](https://semver.org/spec/v2.0.0.html) and ask in `#dps_tech_team` to publish the library.

### Contribution Guidelines

Please fix bugs. :smiley:

For new features we are only interested if they have proven benefits to the wider HMPPS community.

As a rule of thumb new features must:

* Already be implemented in several HMPPS services, i.e. at least 3
* Have been running stably in a production environment, i.e. for at least 3 months
* Provide value to all library consumers, i.e. this isn't the place to handle obscure edge cases

## Publishing Locally (to test against other projects)

* Firstly bump the version of this project in `lib/build.gradle.kts` e.g. increase the minor version by 1 and add `-beta` to the version number.
* Then publish the plugin to local maven

```
./gradlew publishToMavenLocal -x :hmpps-sqs-spring-boot-autoconfigure:signAutoconfigurePublication -x :hmpps-sqs-spring-boot-starter:signStarterPublication 

```

In the other project's Gradle build script change the version to match and it should now be pulled into the project.

## Publishing to Maven Central

[This guide](https://central.sonatype.org/publish/publish-guide/) was used as a basis for publishing to Maven Central.

However, please note that the document above is old and a couple of things have changed.

* The Gradle plugin used in that document - `maven` - is out of date and we use the [maven-publish plugin](https://docs.gradle.org/current/userguide/publishing_maven.html) instead.
* The process described in the document above requires a manual step to release the library from the Nexus staging repository - we have implemented the  [Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin) to automate this step.

### Authenticating with Sonatype

When publishing to Maven Central we authenticate with a username and password.

In order to use groupId (see [Maven coordinates](https://maven.apache.org/pom.html#Maven_Coordinates)) `uk.org.justice.service.hmpps` we claimed the domain `uk.org.justice.service.hmpps` with Sonatype ( [see this PR](https://github.com/ministryofjustice/cloud-platform-environments/pull/4872) ) and registered this against my personal Sonatype username (service accounts not supported). Several members of the `hmpps-tech-team` have accounts associated with that domain too.

An account also gives us access to the [Staging repository](https://s01.oss.sonatype.org/#stagingRepositories) which is used to validate Maven publications before they are published.

#### Handling Failed Publications

If the library fails to be published then it might have failed validation in the Sonatype Staging repository so check there for some clues.

#### Creating a Sonatype User

To get access to the Sonatype domain `uk.org.justice.service.hmpps`:

* [Create a Sonatype user account](https://issues.sonatype.org/secure/Signup!default.jspa)
* Get an existing Sonatype user with access to the domain to [raise a ticket](https://issues.sonatype.org/secure/CreateIssue.jspa) requesting access for the new user account.

#### Adding Credentials to a Publish Request

A valid Sonatype username and password are required to publish to Maven Central. Unfortunately service accounts are not supported by Sonatype so personal user details are required.

In `build.gradle.kts` we use environment variables `OSSRH_USERNAME` and `OSSRH_PASSWORD` to authenticate with Sonatype. These environment variables must be set when running the `publish` task.

Note that this means the environment variables have been [set in Circle CI](https://app.circleci.com/settings/project/github/ministryofjustice/hmpps-spring-boot-sqs/environment-variables). This is safe as environment variables cannot be retrieved from Circle.

#### Changing the Sonatype Credentials

If you need to change the secrets used to authorise with Sonatype delete the Circle CI environment variables (`OSSRH_USERNAME` and `OSSRH_PASSWORD`) and re-add them with the username and password of another Sonatype user with access to the domain.

### Signing a Publish Request to Maven Central

One of the requirements for publishing to Maven Central is that all publications are [signed using PGP](https://central.sonatype.org/publish/requirements/gpg/).

#### Signing a Publication on Circle CI

In `build.gradle.kts` we use environment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` as recommended in the [Gradle Signing Plugin documentation](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:in-memory-keys).

#### Changing the Signing Key

* Generate a new key - follow the [Sonatype guide](https://central.sonatype.org/publish/requirements/gpg/).
* Export the private key to a file - google for `gpg export private key` and you should find several guides for using `gpg --export-secret-keys`.
* To allow the private key to be inserted into Circle, convert the newlines in the private key to `\n` with command (assuming the private key is stored in file `private.key`): `cat private.key | sed -E '/\r{0,1}\n/\\n/g'`
* Delete the environment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` from the [Circle CI env vars page](https://app.circleci.com/settings/project/github/ministryofjustice/hmpps-spring-boot-sqs/environment-variables)
* Recreate the environment variables where `ORG_GRADLE_PROJECT_signingKey` contains the private key (with newlines) and `ORG_GRADLE_PROJECT_signingPassword` contains the passphrase  
