# Contributing to Phoenix

Thanks for helping. This page explains how a change gets from your machine into `main`.

## The short version

1. Open (or pick) an issue that describes the change.
2. Branch from an up-to-date `main`.
3. Make the change, with tests.
4. Run the checks locally.
5. Open a pull request. CI runs automatically; it must be green before merge.

## Requirements

- **JDK 21.** `build.gradle.kts` pins the toolchain to 21; CI uses the same.
- Nothing else to install: the Gradle wrapper (`./gradlew`) downloads Gradle itself.

## Run the checks locally

CI runs exactly this, so if it passes here it passes there:

```bash
./gradlew clean build
```

This compiles the code, runs every test, and builds the distribution. For a quicker loop
while you work:

```bash
./gradlew test                                              # all tests
./gradlew test --tests 'com.phoenix.net.TaskQueueServiceTest'   # one class
```

Test reports are written to `build/reports/tests/test/index.html`.

## What CI checks

| Check | What it verifies | If it fails |
|---|---|---|
| **Build and test** | The code compiles on JDK 21 and every JUnit test passes from a clean checkout. The Gradle wrapper jar is checksum-validated. | Open the failed run, read the failing test in the log or the run summary, reproduce with `./gradlew clean build`. Test reports are attached to the run as the `test-reports` artifact. |
| **Repo hygiene** | No `build/`, `.gradle/`, `out/`, `.class` or `.jar` files are committed (except the wrapper jar) and the diff has no whitespace errors. | Remove the files with `git rm --cached <path>` and make sure `.gitignore` covers them; fix whitespace with `git diff --check`. |

A pull request cannot be merged until both are green.

## Branches and commits

- Branch names: `feat/...`, `fix/...`, `refactor/...`, `docs/...`, `chore/...`
- Commit and PR titles follow the same prefixes, in the imperative:
  `fix: reject results from abandoned attempts`
- One logical change per pull request. Small PRs are reviewed faster.

## Tests

- A new behaviour needs a test; a bug fix needs a test that fails without the fix.
- Tests must be deterministic. Prefer the injectable `Clock` / id-supplier seams the Core
  already provides over `Thread.sleep`, and never depend on a fixed network port: bind to
  port `0` and read back the real one.
- A test that is "sometimes red" is a bug in the test. Do not re-run until it passes;
  open an issue.

## Pull requests

- Fill in the template: what changed, why, how it was tested.
- Link the issue (`Closes #123`).
- Describe any impact on existing node agents (Linux, Android) under *Compatibility notes*.
- Keep the PR green: push fixes to the same branch, CI re-runs automatically.

## Dependencies

Dependabot proposes weekly updates for Gradle dependencies and GitHub Actions. Review them like
any other change: CI must pass before merging.
