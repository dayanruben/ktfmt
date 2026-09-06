# Releasing a New `ktfmt` Version

1. Make sure to have bumped the version in a separated diff, taking care of the `CHANGELOG.md` file (see examples in commit history).
2. Create a new Release in GitHub. A GitHub Action is automatically triggered and builds and publishes the artifacts to
    1. Maven
    2. IntelliJ Plugin marketplace

## Snapshot Publishing

When `ktfmt.version` in `gradle.properties` ends with `-SNAPSHOT`, `:ktfmt` snapshots can be published to Sonatype's snapshots repository with:

```
./gradlew :ktfmt:publishMavenPublicationToSonatypeSnapshotsRepository
```

The snapshot repository is only registered for `-SNAPSHOT` versions and uses the same OSSRH (`OSSRH_USERNAME`/`OSSRH_PASSWORD`) and GPG (`SIGN_BUILD`, signing credentials) secrets as the release workflow. It only runs the direct Maven snapshot publication task; it does not close or release staging repositories.
