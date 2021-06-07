# hmpps-spring-boot-sqs

A helper library providing utilities for using `amazon-sqs-java-messaging-lib`

## THIS IS A WORK IN PROGRESS

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
* Get an existing Sonatype user with access to the domain to [raise a ticket](https://issues.sonatype.org/secure/CreateIssue!enticatingdefault.jspa) requesting access for the new user account.

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
