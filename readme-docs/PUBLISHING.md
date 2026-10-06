# Publishing This Library

This document is for maintainers of `hmpps-spring-boot-sqs` who need to publish a new version to Maven Central. It is
not needed if you're just consuming the library — see [README.md](../README.md) for that.

## How To Contribute To This Library

See [CONTRIBUTING.md](CONTRIBUTING.md) for the process and guidelines for raising a PR.

If your PR is accepted, make sure the version number in `build.gradle.kts` has been bumped according to
[Semver rules](https://semver.org/spec/v2.0.0.html) before it's published.

## Publishing Locally (to test against other projects)

* Firstly bump the version of this project in `build.gradle.kts`, e.g. increase the minor version by 1 and add
  `-beta` to the version number.
* Then publish the plugin to your local maven repository:

```
./gradlew publishToMavenLocal
```

In the other project's Gradle build script change the version to match and it should now be pulled into the project.

## Publishing to Maven Central

Publishing is automated via GitHub Actions:

* [`.github/workflows/pipeline.yml`](../.github/workflows/pipeline.yml) runs on every push to any branch. It always
  runs the test suite (via the shared `gradle_verify` reusable workflow); only when the push is to `main` does it
  then call `publish.yml`.
* [`.github/workflows/publish.yml`](../.github/workflows/publish.yml) runs
  `./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository`, using secrets scoped to a GitHub
  [Environment](https://docs.github.com/en/actions/deployment/targeting-different-environments/using-environments-for-deployment)
  named `publish`.
* That `publish` environment has required reviewers configured, so publishing a new version still requires someone
  with access to manually approve the GitHub Actions run before it proceeds — this is the modern equivalent of the
  old CircleCI manual-approval step. If you do not have permission to approve this step please ask in Slack channel
  `#kotlin-dev` to find someone that does.

### Published Version Numbers

Please be aware that once the jar is published to Maven Central other teams can use that version. They may even
upgrade to the new version automatically with some fancy tooling.

Use some common sense when changing the version number and publishing:

* Try NOT to introduce breaking changes. Be creative, there are often ways around this. If a change is breaking,
  describe it clearly in the [release notes](../release-notes) for the relevant major version.
* Use [semantic versioning](https://semver.org/) to indicate the scope of the change.
* You might think you can only test your change in the wild — consider
  [testing locally on other projects](#publishing-locally-to-test-against-other-projects) first.
* If you must test in the wild, add a suffix to the version number such as `-beta` or `-wip` to indicate the change is
  not considered stable.

## Technical Details of Publishing to Maven Central

[This guide](https://central.sonatype.org/publish/publish-guide/) was used as a basis for publishing to Maven Central.

However, please note that the document above is old and a couple of things have changed:

* The Gradle plugin used in that document — `maven` — is out of date and we use the
  [maven-publish plugin](https://docs.gradle.org/current/userguide/publishing_maven.html) instead.
* The process described in the document above requires a manual step to release the library from the Nexus staging
  repository — we have implemented the [Nexus Publish Plugin](https://github.com/gradle-nexus/publish-plugin) to
  automate this step.

### Authenticating with Sonatype

TODO - this has all changed recently and needs reworking.

When publishing to Maven Central we authenticate with a username and password.

In order to use groupId (see [Maven coordinates](https://maven.apache.org/pom.html#Maven_Coordinates))
`uk.gov.justice.service.hmpps` we claimed the domain `uk.gov.justice.service.hmpps` with
Sonatype ( [see this PR](https://github.com/ministryofjustice/cloud-platform-environments/pull/4872) ) and registered
this against a personal Sonatype username (service accounts not supported). Several members of the former
`dps-tech-team` have accounts associated with that domain too — ask in Slack channel `#kotlin-dev` to find such people.

An account also gives us access to the [Staging repository](https://s01.oss.sonatype.org/#stagingRepositories) which is
used to validate Maven publications before they are published.

#### Handling Failed Publications

If the library fails to be published then it might have failed validation in the Sonatype Staging repository, so
check there for some clues.

#### Creating a Sonatype User

To get access to the Sonatype domain `uk.gov.justice.service.hmpps`:

* [Create a Sonatype user account](https://issues.sonatype.org/secure/Signup!default.jspa)
* Get an existing Sonatype user with access to the domain to
  [raise a ticket](https://issues.sonatype.org/secure/CreateIssue.jspa) requesting access for the new user account.

#### Adding Credentials to a Publish Request

A valid Sonatype username and password are required to publish to Maven Central. Unfortunately service accounts are
not supported by Sonatype so personal user details are required.

In `build.gradle.kts` we use environment variables `OSSRH_USERNAME` and `OSSRH_PASSWORD` to authenticate with
Sonatype. These must be set when running the `publish` task, and are supplied by the `publish` GitHub Actions
Environment (see above) when the pipeline runs — they are not exposed outside of that environment's protected runs.

#### Changing the Sonatype Credentials

If you need to change the secrets used to authorise with Sonatype, update the `OSSRH_USERNAME` and `OSSRH_PASSWORD`
secrets on the repository's `publish` GitHub Actions Environment (Settings → Environments → `publish`) with the
username and password of another Sonatype user with access to the domain.

### Signing a Publish Request to Maven Central

One of the requirements for publishing to Maven Central is that all publications are
[signed using PGP](https://central.sonatype.org/publish/requirements/gpg/).

#### Signing a Publication in GitHub Actions

In `build.gradle.kts` we use environment variables `ORG_GRADLE_PROJECT_signingKey` and
`ORG_GRADLE_PROJECT_signingPassword` as recommended in the
[Gradle Signing Plugin documentation](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:in-memory-keys).
These are also supplied as secrets on the `publish` GitHub Actions Environment.

#### Changing the Signing Key

* Generate a new key — follow the [Sonatype guide](https://central.sonatype.org/publish/requirements/gpg/).
* Export the private key to a file — google for `gpg export private key` and you should find several guides for
  using `gpg --export-secret-keys`.
* To allow the private key to be inserted as a secret, make sure newlines in the private key are `\n`.
* Update the `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` secrets on the repository's
  `publish` GitHub Actions Environment, where `ORG_GRADLE_PROJECT_signingKey` contains the private key (with
  newlines) and `ORG_GRADLE_PROJECT_signingPassword` contains the passphrase.
