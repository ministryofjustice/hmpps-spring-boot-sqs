# Contributing To This Library

Raise a PR and ask for a review in the MOJDT Slack channel `#kotlin-dev`.

If accepted, make sure that the version number in `build.gradle.kts` has been upgraded according to
[Semver rules](https://semver.org/spec/v2.0.0.html) and ask in `#kotlin-dev` to publish the library — see
[PUBLISHING.md](PUBLISHING.md) for the full publishing process.

## Contribution Guidelines

Please fix bugs. :smiley:

For new features we are only interested if they have proven benefits to the wider HMPPS community.

As a rule of thumb new features must:

* Already be implemented in several HMPPS services, i.e. at least 3
* Have been running stably in a production environment, i.e. for at least 3 months
* Provide value to all library consumers, i.e. this isn't the place to handle obscure edge cases
