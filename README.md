# hmpps-spring-boot-sqs

A helper library providing utilities for using `amazon-sqs-java-messaging-lib`

## :construction: THIS IS A WORK IN PROGRESS :construction:

This library is currently being developed and tested within the HMPPS Tech Team and is not currently intended for wider consumption. Please wait for an official v1.0.0.

## Overview

We have many services that use AWS SQS queues and various patterns for managing queues have evolved over time. These patterns have been duplicated widely and thus are subject to the usual problems associated with a lack of DRY such as code drift and the proliferation of boilerplate code.

This library is intended to capture the most common patterns and make them easy to distribute among other projects. The goal is to provide various queue management and configuration tasks out of the box.

## How To Use This Library

Find the latest published version of the library by searching on Maven Central for `hmpps-spring-boot-sqs`.  (If you can't find the version mentioned in `/lib/build.gradle.kts` please be patient, it can take a while to publish to Maven Central).

Add the following dependency to your Gradle build script:

*Kotlin*

``` kotlin
implementation("uk.gov.justice.service.hmpps:hmpps-spring-boot-sqs:<library-version>")
```

*Groovy*

``` groovy
implementation 'uk.gov.justice.service.hmpps:hmpps-spring-boot-sqs:<library-version>'
```

Then create some properties defining the queue(s) in the application. See [HMPPS Queue Properties](#hmpps-queue-properties) for information on the properties, and check the [test-app](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/tree/main/test-app) for an example.

Hopefully you will now get features like HealthIndicators, queue admin endpoints and AmazonSQS beans for free. Read more about all available features [here](#features).

## How To Run This Locally

A test application that applies this library exists for both functional tests and as an example application that uses this library.

See [Running All Tests](#running-all-tests) and [Running the test-app](#running-the-test-app) for more details.

## Features

### HMPPS Queue Properties

This library is driven by some configuration properties prefixed `hmpps.sqs` that are loaded into class `HmppsQueueProperties`. Based on the properties defined the library will attempt to:

* create `AmazonSQS` beans for each queue defined
* create a `HealthIndicator` for each queue which is registered with Spring Boot Actuator and appears on your `/health` page
* add `HmpspQueueResource` to the project which provides endpoints for retrying DLQ messages and purging queues
* create LocalStack queues for testing against

Examples of property usage can be found in the test project in the following places:

* Production: [test-app/.../values.yaml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/values.yaml#L33)
* Running locally with LocalStack: [test-app/.../application-localstack.yml](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/resources/application-localstack.yml)
* Integration Test: [test-app/.../application-test.yml#L8](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml#L8)

#### HmppsQueueProperties Definitions

| Property | Default | Description |
| -------- | ------- | ----------- |
| provider | `aws` | `aws` for production or `localstack for` running locally / integration tests. (Testcontainers is not yet supported. Possibly coming soon). |
| region   | `eu-west-2` | The AWS region where the queues live. |
| localstackUrl | `http://localhost:4566` | Only used for `provider=localstack`. The location of the running LocalStack instance. |
| queues   | | A map of `queueId` to `QueueConfig`. One entry is required for each queue. In production these are derived from environment variables with the prefix `HMPPS_SQS_QUEUES_` that should be populated from Kubernetes secrets (see below).

Each queue defined in the `queues` map is defined in the `QueueConfig` property class

| Property | Default | Description |
| -------- | ------- | ----------- |
| queueId | | The key to the `queues` map. A unique name for the queue configuration, used heavily when automatically creating Spring beans. |
| queueName | | The name of the queue as recognised by AWS or LocalStack. |
| queueAccessKeyId | | Only used for `provider=aws`. The AWS access key ID, should be derived from an environment variable of format `HMPPS_SQS_QUEUE_<queueName>_ACCESS_KEY_ID`. |
| queueSecretAccessKey | | Only used for `provider=aws`. The AWS secret access key, should be derived from an environment variable of format `HMPPS_SQS_QUEUE_<queueName>_SECRET_ACCESS_KEY`. |
| asyncQueueClient | false | If true then the `AmazonSQS` bean created will be an `AmazonSQSAsync` instance. |
| dlqName | | The name of the queue's dead letter queue (DLQ) as recognised by AWS or LocalStack. |
| dlqAccessKeyId | | Only used for `provider=aws`. The AWS access key ID of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUE_<queueName>_DLQ_ACCESS_KEY_ID`. |
| dlqSecretAccessKey | | Only used for `provider=aws`. The AWS secret access key of the DLQ, should be derived from an environment variable of format `HMPPS_SQS_QUEUE_<queueName>_DLQ_SECRET_ACCESS_KEY`. |
| asyncDlqClient | false | If true then the `AmazonSQS` bean created will be an `AmazonSQSAsync` instance. |

### AmazonSQS Beans

Each queue defined in `HmppsQueueProperties` should have an `AmazonSQS` created for both the main queue and the associated dead letter queue (DLQ).

The bean names have the following format and can be used with `@Qualifier` to inject the beans into another `@Component`:

* main queue - `<queueId>-sqs-client`
* DLQ - `<queueId>-sqs-dlq-client`

#### Overriding AmazonSQS Beans

If for any reason you don't want to use the `AmazonSQS` beans automatically created by this library but still want other features such as a `HealthIndicator` or queue admin endpoints then it's possible to override them.

At the point this library attempts to generate any bean and register with the `ApplicationContext` if it finds an existing bean with the same name then it does nothing and uses the existing bean.

So first find the bean names you wish to override as mentioned in [AmazonSQS Beans](#amazonsqs-beans). Then create your own AmazonSQS bean with the same name.

#### I Don't Need a DLQ. Why Is It Mandatory?

Queues without dead letter queues are currently unsupported by this library as it is considered an edge case based on current practises in HMPPS.

If this is a blocker to you using this library contact `#dps_tech_team` on MOJDT Slack.

#### SpyBeans

It is only possible to use the `@SpyBean` annotation for beans declared in a `@Configuration` class (unless you do some hacking with the Spring lifecycle support). As the library creates these beans on an ad hoc basis it is not possible to create spies for them.

However, as mentioned above you can override the automatically generated beans with your own bean, e.g. with bean name `<queueId>-sqs-client` or `<queueId>-sqs-dlq-client`. If you do this in a `@TestConfiguration` using the `@Bean` annotation then it is possible to declare a corresponding SpyBean. `HmppsQueueFactory` provides factory methods to assist in creating such beans.

See an example in the `test-app` class [IntegrationTestBase](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/integration/IntegrationTestBase.kt).

#### MockBeans

MockBeans have no benefit over SpyBeans but they cause Spring to reload the application with a fresh context slowing down your tests. They probably work with this library like SpyBeans do but this hasn't been tested.

Consider using a SpyBean instead and declaring it in the base IntegrationTest class which then allows you to mock and/or verify or neither depending upon the requirements of each test.

#### Random Queue Names

If you look in the `test-app`'s [application properties](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/test/resources/application-test.yml) you can see that it uses random queue names.

When `provider=localstack` the queues are created in LocalStack as soon as the `AmazonSQS` beans are created. By using random queue names we can ensure that if Spring loads a new context during integration testing then the new context gets new queues which cannot interfere with tests from another context.

If you need to know the actual queue names used you can find them in the Spring logs. You can also see them in LocalStack with command:

`AWS_ACCESS_KEY_ID=foobar AWS_SECRET_ACCESS_KEY=foobar aws --endpoint-url=http://localhost:4566 --region=eu-west-2 sqs list-queues`

### JmsListenerContainerFactory

To read from a queue with JMS we need a `JmsListenerContainerFactory` for each queue which can then be referenced in the `@JmsListener` annotation on the `containerFactory` attribute.

This library will create a container factory for each queue defined in `HmppsQueueProperties` and save them in proxy class `HmppsQueueContainerFactoryProxy`. The proxy can then be defined as the `containerFactory`.

It should also be noted that the `@EnableJms` annotation is also included by this library.

This means that the only thing you need to do to get a JMS listener working for each queue in `HmppsQueueProperties` is use the `HmppsQueueContainerFactoryProxy` container factory.

An example is available in the `test-app`'s (listeners)[https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/src/main/kotlin/uk/gov/justice/digital/hmpps/hmppstemplatepackagename/service/MessageListener.kt].

#### Overriding the JmsListenerContainerFactory

If you don't wish to use the `HmppsQueueContainerFactoryProxy` because you want to configure your listener in a different way then simply create your own `DefaultJmsListenerContainerFactory` and reference it on the `@JmsListener` annotation.

### Queue Health

All queues should be included on an application's health page. An unhealthy queue indicates an unhealthy service.

For each queue defined in `HmppsQueueProperties` we create a `HmppsQueueHealth` bean.

The Spring beans produced have names of format `<queueId>-health`. If you wish to override the automatically created bean then provide a custom bean with the same name. Upon finding the custom bean this library will use the custom bean rather than generating one.

#### Testing Queue Health

Unit tests for the generic queue health exist in this library so there is no need to add more.

You should however create a couple of integration tests for your queue health in case your implementation has problems. Examples are available in the `test-app` - see classes:

* happy path - `QueueHealthCheckTest`
* negative path - `QueueHealthCheckNegativeTest`

### Queue Admin Endpoints

When SQS messages fail to be processed by the main queue they are sent to the Dead Letter Queue (DLQ). We then find ourselves in one of the following scenarios:

* The failure was transient and a retry will allow the message to be processed
* The failure was due to an unrecoverable error and we want to discard the message

Class `HmppsQueueResource` provides endpoints to retry and purge messages on a DLQ.

#### Usage

For transient errors we would typically create a Kubernetes Cronjob to automatically retry all DLQ messages. The Cronjob should be configured to run before [an alert triggers for the age of the DLQ message](https://github.com/ministryofjustice/cloud-platform-environments/blob/main/namespaces/live-1.cloud-platform.service.justice.gov.uk/offender-events-prod/09-prometheus-sqs-sns.yaml#L13) - typically every 10 minutes. See the [example Cronjob](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml) for more details.

Unrecoverable errors should be fixed such that they no longer fail and are not sent to the DLQ. In the meantime these can be removed by purging the DLQ to prevent the alert from firing.

#### How Do I find The DLQ/Queue Name?

The queue names are generally defined in Kubernetes secrets for the namespace which are then mapped into the Spring Boot application as configuration properties.

The queue names should also appear on the `/health` page if using this library for queue health.

#### Securing Endpoints

Most endpoints in `HmppsQueueResource` will have a default role required to access them which is overridable by a configuration property.

Note that any endpoints defined in `HmppsQueueResource` that are not secured by a role are only intended for use within the Kubernetes namespace and must not be left wide open - instead they should be secured in the Kubernetes ingress. See the [example ingress](https://github.com/ministryofjustice/hmpps-spring-boot-sqs/blob/main/test-app/helm_deploy/hmpps-template-kotlin/example/housekeeping-cronjob.yaml) for how to block the endpoints from outside the namespace.

#### Open API Docs

We do not provide any detailed Open API documentation for these endpoints. This is because there is a variety of Open API document generators being used at different versions and catering for them all would require a complicated solution for little benefit.

Hopefully your Open API document generator can find the endpoints automatically and includes them in the Open API docs. If not you may have to introduce some configuration to point the generator at the endpoints, for example using the Springfox [ApiSelectorBuilder#apis method](https://springfox.github.io/springfox/docs/snapshot/#springfox-spring-mvc-and-spring-boot) to add the base package `uk.gov.justice.hmpps.sqs`.

## Modules

We are using a multi-module project in order to create functional tests that use the imported library.

### lib

This is the module that generates the library for publishing. See the publish job in Circle build `/.circleci/config.yml` for more details.

#### Running the Unit Tests

To run only the unit tests found in the `lib` module use command:

`./gradlew lib:test`

#### Running All Tests

To run all tests including the functional tests in the module `test-app` use this command to start localstack:

`docker-compose -f docker-compose-test.yml up localstack`

And this command to run the tests:

`./gradlew test`

### test-app

This module contains a copy of the [Kotlin template project](https://github.com/ministryofjustice/hmpps-template-kotlin) with the library included as a dependency. This means there is a lot of stuff in the `test-app` that isn't needed for the tests, such as the Circle config.yml - these have been left on purpose so that it is easier to compare the test app with the template project when attempting to keep the test app up to date.

Various queue related functionality has been added to the template project so that we can run tests against the library.

Note that this module does not produce an artifact for publishing - we only publish the library from the `lib` module.

#### Running the Functional Tests

The tests require that localstack is running. To start localstack use command:

`docker-compose -f docker-compose-test.yml up localstack`

From the root of the project run the following command to test only the test-app tests:

`./gradlew test-app:test`

### Running the test-app

Running the `test-app` locally can be useful for debugging features provided by this library.

Start localstack with command:

`docker-compose -f docker-compose-test.yml up localstack`

Then run the `test-app` in your IDE from main class `HmppsTemplateKotlin`.

#### Managing Queues in Tests

You will find that this project doesn't contain a set-up script to create localstack resources. This is because there's an issue with using `@SpringBootTest` where if tests running in different Spring contexts share the same queue, the message listener carries on reading from the queue even when the context is not currently active. This results in contamination between tests - sometimes testB fails because the message it was expecting has been processed by testA's message listener.

To make sure that each Spring context works in isolation we create the localstack resources with random names when starting the application.

##### Mechanism

The application configuration properties in `application-test.yml` set the queue and DLQ names to `${random.uuid}`. This means every time the property is read it generates a random queue/DLQ name.

The configuration properties are loaded into class `HmppsQueueProperties`. This is to guarantee that the queue names are only generated once per context, in the properties bean.

The class `HmpspQueueFactory` then takes the queue names from `HmpspQueueProperties` and creates the queues during application startup.

The JMS message listener defined in class `MessageListener` sets the destination queue name from the `HmppsQueueProperties` bean. Note that due to the way Spring loads `@ConfigurationProperties` beans some complicated `SpEL` is required to define the queue name in the `@JmsListener` annotation. See the note about the convention `<prefix>-<fqn>` in [the Spring documentation](https://docs.spring.io/spring-boot/docs/2.1.13.RELEASE/reference/html/boot-features-external-config.html#boot-features-external-config-typesafe-configuration-properties).

## How To Contribute To This Library

Raise a PR and ask for a review in the MOJ Slack channel `#dps_dev`.

If accepted make sure that the version number in `lib/build.gradle.kts` has been upgraded according to [Semver rules](https://semver.org/spec/v2.0.0.html) and ask in `#dps_tech_team` to publish the library.

### Contribution Guidelines

Please fix bugs. :smiley:

For new features we are only interested if they have proven benefits to the wider HMPPS community.

As a rule of thumb new features must:

* Already be implemented in several HMPPS services, i.e. at least 3
* Have been running stably in a production environment, i.e. for at least 3 months
* Provide value to all library consumers, i.e. this isn't the place to handle obscure edge cases

## Publishing Locally (to test against other projects)

* Firstly bump the version of this project in `lib/build.gradle.kts`.
* Then publish the plugin to local maven

```
./gradlew publishToMavenLocal -x :lib:signMavenPublication
```

In the other project's Gradle build script change the version to match and it should now be pulled into the project.

## Publishing to Maven Central

[This guide](https://central.sonatype.org/publish/publish-guide/) was used as a basis for publishing to Maven Central.

However, please note that the document above is old and a couple of things have changed.

* The Gradle plugin used in that document - `maven` - is out of date and we use the [maven-publish plugin](https://docs.gradle.org/current/userguide/publishing_maven.html) instead.
* The process described in the document above requires a manual step to release the library from the Nexus staging repository - we have implemented the  [Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin) to automate this step.

### Authenticating with Sonatype

When publishing to Maven Central we authenticate with a username and password.

In order to use groupId (see [Maven coordinates](https://maven.apache.org/pom.html#Maven_Coordinates)) `uk.org.justice.service.hmpps` we claimed the domain `uk.org.justice.service.hmpps` with Sonatype [see this PR](https://github.com/ministryofjustice/cloud-platform-environments/pull/4872) and registered this against my personal Sonatype username (service accounts not supported :( ). By the time you read this several members of the `hmpps-tech-team` will also have accounts associated with that domain.

An account also gives us access to the [Staging repository](https://s01.oss.sonatype.org/#stagingRepositories) which is used to validate Maven publications before they are published.

Note that this is the place to look for clues if the publish fails. ^^^

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

However we wish to publish from Circle CI rather than locally so we have to configure things a little differently.

#### Signing a Publication on Circle CI

In `build.gradle.kts` we use environment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` as recommended in the [Gradle Signing Plugin documentation](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:in-memory-keys).

#### Changing the Signing Key

* Generate a new key - follow the [Sonatype guide](https://central.sonatype.org/publish/requirements/gpg/).
* Export the private key to a file - google for `gpg export private key` and you should find several guides for using `gpg --export-secret-keys`.
* To allow the private key to be inserted into Circle, convert the newlines in the private key to `\n` with command (assuming the private key is stored in file `private.key`): `cat private.key | sed -E ':a;N;$!ba;s/\r{0,1}\n/\\n/g'`
* Delete the environment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` from the [Circle CI env vars page](https://app.circleci.com/settings/project/github/ministryofjustice/hmpps-spring-boot-sqs/environment-variables)
* Recreate the environment variables where `ORG_GRADLE_PROJECT_signingKey` contains the private key (with newlines) and `ORG_GRADLE_PROJECT_signingPassword` contains the passphrase  
