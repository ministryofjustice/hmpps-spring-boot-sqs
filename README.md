# hmpps-spring-boot-sqs

A helper library providing utilities for using `amazon-sqs-java-messaging-lib`

## THIS IS A WORK IN PROGRESS

## Modules

We are using a multi-module project in order to create functional tests that use the imported library.

### lib

This is the module that generates the library for publishing. See the publish job in Circle build `/.circleci/config.yml` for more details.

#### Running the tests

To run only the unit tests found in the `lib` module use command:

`./gradlew lib:test`

To run all tests including the functional tests in the module `test-app` use this command to start localstack:

`docker-compose -f docker-compose-test.yml up localstack`

And this command to run the tests:

`./gradlew test`

### test-app

This module contains a copy of the [Koktlin template project](https://github.com/ministryofjustice/hmpps-template-kotlin) with the library included as a dependency. This means there is a lot of stuff in the `test-app` that isn't needed for the tests, such as the Circle config.yml - these have been left on purpose so that it is easier to compare the test app with the template project when attempting to keep the test app up to date.

Various queue related functionality has been added to the template project so that we can run tests against the library.

Note that this module does not produce an artifact for publishing - we only publish the library from the `lib` module.

#### Running the tests

The tests require that localstack is running. To start localstack use command:

`docker-compose -f docker-compose-test.yml up localstack`

From the root of the project run the following command to test only the test-app tests:

`./gradlew test-app:test`

#### Managing Queues in Tests

You will find that this project doesn't contain a set-up script to create localstack resources. This is because there's an issue with using `@SpringBootTest` where if tests running in different Spring contexts share the same queue, the message listener carries on reading from the queue even when the context is not currently active. This results in contamination between tests - sometimes testB fails because the message it was expecting has been processed by testA's message listener.

To make sure that each Spring context works in isolation we create the localstack resources when starting the application.

##### Mechanism

The application configuration properties in `application.yml` set the queue and DLQ names to `${random.uuid}`. This means every time the property is read it generates a random queue/DLQ name.

The configuration properties are loaded into class `SqsConfigProperties`. This is to guarantee that the queue names are only generated once per context, in the properties bean.

The configuration class `SqsConfig` then takes the queue names from `SqsConfigProperties` and creates the queues during application startup.

The JMS message listener defined in class `MessageListener` sets the destination queue name from the `SqsConfigProperties` bean. Note that due to the way Spring loads `@ConfigurationProperties` beans some complicated `SpEL` is required to define the queue name in the `@JmsListener` annotation. See the note about the convention `<prefix>-<fqn>` in [the Spring documentation](https://docs.spring.io/spring-boot/docs/2.1.13.RELEASE/reference/html/boot-features-external-config.html#boot-features-external-config-typesafe-configuration-properties).

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

In order to use groupId (see [Maven coordinates](https://maven.apache.org/pom.html#Maven_Coordinates)) `uk.org.justice.service.hmpps` we claimed the domain `uk.org.justice.service.hmpps` with Sonatype [see this PR](https://github.com/ministryofjustice/cloud-platform-environments/pull/4872) and registered this against my personal Sonatype username (service accounts not suported :( ). By the time you read this several members of the `hmpps-tech-team` will also have accounts associated with that domain.

An account also gives us access to the [Staging repository](https://s01.oss.sonatype.org/#stagingRepositories) which is used to validate Maven publications before they are published.

Note that this is the place to look for clues if the publish fails. ^^^

#### Creating a Sonatype User

To get access to the Sonatype domain `uk.org.justice.service.hmpps`:

* [Create a Sonatype user accout](https://issues.sonatype.org/secure/Signup!default.jspa)
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

In `build.gradle.kts` we use environmenvironment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` as recommended in the [Gradle Signing Plugin documentation](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:in-memory-keys).

#### Changing the Signing Key

* Generate a new key - follow the [Sonatype guide](https://central.sonatype.org/publish/requirements/gpg/).
* Export the private key to a file - google for `gpg export private key` and you should find several guides for using `gpg --export-secret-keys`.
* To allow the private key to be inserted into Circle, convert the newlines in the private key to `\n` with command (assuming the private key is stored in file `private.key`): `cat private.key | sed -E ':a;N;$!ba;s/\r{0,1}\n/\\n/g'`
* Delete the environment variables `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` from the [Circle CI env vars page](https://app.circleci.com/settings/project/github/ministryofjustice/hmpps-spring-boot-sqs/environment-variables)
* Recreate the environment variables where `ORG_GRADLE_PROJECT_signingKey` contains the private key (with newlines) and `ORG_GRADLE_PROJECT_signingPassword` contains the passphrase  
