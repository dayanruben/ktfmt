# Contributing to ktfmt
We want to make contributing to this project as easy and transparent as
possible.

**If you plan a significant change, please first start a discussion as a GitHub Issue.**

## Pull Requests
We actively welcome your pull requests.

1. Fork the repo and create your branch from `main`.
2. If you've added code that should be tested, add tests.
3. If you've changed APIs, update the documentation.
4. Ensure the test suite passes.
5. Make sure your code lints.
6. If applicable add relevant change information to the changelog

## Issues
We use GitHub issues to track public bugs. Please ensure your description is
clear and has sufficient instructions to be able to reproduce the issue.

## Publishing snapshots

Release automation can publish a Maven snapshot after a successful build of the default branch.
The Maven repository is available when `ktfmt.version` ends in `-SNAPSHOT` and the snapshot job
enables it explicitly.

Use a Central Portal user token with permission to publish snapshots for the `org.jetbrains.kotlinx`
namespace. Provide the token's username and password as
`ORG_GRADLE_PROJECT_sonatypeSnapshotsUsername` and
`ORG_GRADLE_PROJECT_sonatypeSnapshotsPassword`, then run the publication task.

```shell
./gradlew -Pktfmt.publishSonatypeSnapshots=true :ktfmt:publishMavenPublicationToSonatypeSnapshotsRepository
```

See the [Central Portal snapshot guide](https://central.sonatype.org/publish/publish-portal-snapshots/)
for namespace setup and how to consume `org.jetbrains.kotlinx:ktfmt` snapshots.

## License
By contributing to ktfmt, you agree that your contributions will be licensed
under the LICENSE file in the root directory of this source tree.
