# Contributing

## Development setup

Phoenix Core requires JDK 21. Gradle is provided by the wrapper.

```bash
./gradlew clean build    # compile, test, package
./gradlew test --tests 'com.phoenix.net.TaskQueueServiceTest'
```

Test reports are written to `build/reports/tests/test/index.html`.

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
