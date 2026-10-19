# Contributing

## Development setup

Phoenix Core requires JDK 21. Gradle is provided by the wrapper.

```bash
./gradlew clean build    # compile, test, Javadoc, package
./gradlew test --tests 'com.phoenix.net.TaskQueueServiceTest'
```

Test reports are written to `build/reports/tests/test/index.html`.

The MQTT broker integration tests (`MqttMosquittoIntegrationTest`) start a real
Mosquitto with the files in `deploy/mosquitto`. They need `mosquitto`,
`mosquitto_passwd`, `openssl` and `bash`, and are skipped when any is missing:

```bash
sudo apt install mosquitto      # Debian/Ubuntu; CI installs it too
brew install mosquitto          # macOS
```

The build treats compiler warnings as errors (`-Xlint:all -Werror`) and
`check` builds the Javadoc with doclint, so a broken `{@link}` or malformed
HTML in a doc comment fails the build.

## Continuous integration

Every pull request and every push to `main` runs:

- **Build and test**: `./gradlew clean build` on JDK 21.
- **Repo hygiene**: rejects tracked build output and whitespace errors.

Both checks must pass before a pull request is merged.

## Conventions

- Branches and commit titles use a type prefix: `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `chore`.
- Keep pull requests to a single change, with tests for new behaviour and bug fixes.
- Tests must be deterministic: use the injectable `Clock` and id-supplier seams rather than
  `Thread.sleep`, and bind servers to port `0`.
- Javadoc describes behaviour and contracts. Issue history belongs in commit messages and
  pull requests, not in doc comments.

## Releasing

1. Set `version` in `build.gradle.kts` and add a matching section to `CHANGELOG.md`.
2. Merge to `main` with CI green.
3. Tag the merge commit and push the tag:

   ```bash
   git tag -a v0.2.1 -m "Phoenix Core v0.2.1"
   git push origin v0.2.1
   ```

The `Release` workflow verifies that the tag matches the project version, builds,
and publishes a GitHub release with the distribution archives and the changelog section.
