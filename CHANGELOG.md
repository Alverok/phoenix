# Changelog

All notable changes to Phoenix Core. Versions follow [Semantic Versioning](https://semver.org/);
while the major version is 0, minor versions may change the node-agent protocol.

## [0.2.1] - 2026-10-14

Hardening release that closes the v0.2 Core milestone before the MQTT/TLS transport work.

### Fixed

- `./gradlew run -Dphoenix.*` now forwards every `phoenix.*` property to Core.
  Previously only `phoenix.advertiseAddress` reached it, so every other documented
  setting was silently ignored when Core was started through Gradle.
- A push node that answers with a malformed result body now fails the attempt as
  `TRANSPORT_ERROR`. Previously the parse error escaped the transport and left the
  attempt non-terminal in the lifecycle ledger.
- `GreedyCostScheduler` always selects an eligible node, even when a cost is `NaN`,
  instead of failing with a `NullPointerException`.
- The ESP32 adapter no longer reports a fabricated `SUCCESS`; with no ESP32 agent
  available it fails the attempt with `TRANSPORT_ERROR` so the task is re-placed.
- A node registered with an adapter type Core cannot resolve fails its own attempt
  and the task moves to another node, instead of the error escaping the placement cycle.
- A heartbeat without `cpuUsagePercent` is recorded as unknown (`-1`), not as 0 % load.
- Error responses from the gateway are valid JSON even when the message spans lines.

### Changed

- Request bodies on every endpoint are limited to 64 KiB; larger bodies get `413`.
- Registration requires `adapterType` and rejects a `callbackPort` outside 1-65535.
- `phoenix.maxAttempts`, `phoenix.maxRecoveryCycles` and `phoenix.noNodeWaitSeconds`
  are validated at startup.
- The gateway port is configurable with `phoenix.port` (default `8080`).
- The console's task journal keeps at most 256 tasks.
- Task bodies for both HTTP transports are built in one place (`TaskWire`), with one
  JSON escaper (`JsonText`).

### Removed

- Unused `NoEligibleNodeException`, `TaskDispatcher.dispatch(...)` and
  `TaskRecoveryManager.chooseAlternative(...)`.
- The unused `NEW` and `REGISTERING` node states; registration is synchronous.

### Build and documentation

- Compilation runs with `-Xlint:all -Werror`; the code base compiles without warnings.
- `./gradlew check` builds the Javadoc with doclint, which previously failed.
- Tagged releases are built and published by the `Release` workflow.
- README: configuration reference, operator console keys, package overview, corrected
  task body (`taskId`). Push protocol document rewritten to match the implementation.

## [0.2.0]

Core hardening milestone: placement reservations, delivery versus execution timeout
semantics, transport-neutral attempt lifecycle, in-flight recovery when a node goes
`OFFLINE`, bounded recovery with a fresh waiting window, and attempt identity fencing
negotiated per node (`attempt-id`).

[0.2.1]: https://github.com/Alverok/phoenix/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Alverok/phoenix/releases/tag/v0.2.0
