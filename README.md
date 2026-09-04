# Phoenix — Day 1 Prototype

A lightweight, IoC-based middleware and orchestrator for heterogeneous
legacy edge devices. This is the Day 1 vertical slice: **interface →
reference adapters → Guice-wired Core**, nothing else yet.

## What this proves

`App.java` never imports `LinuxAdapter`, `AndroidAdapter`, or `Esp32Adapter`.
It only knows `IHardwareAdapter`. Guice decides which concrete class to
hand back, based on a `-Dphoenix.adapter=...` flag — so hardware classes
can be swapped **without touching or recompiling Core code**. That's the
IoC/modularity claim from Section 3 of the Design & Solution doc, made
executable.

## Requirements

- JDK 21 (already what this project targets — see note in `build.gradle.kts`
  about revisiting 17 vs 21 once we test JIVE compatibility)
- No global Gradle install needed once the wrapper is generated (see below)

## First-time setup

This repo ships the Gradle *build files* but not the wrapper binary
(`gradlew`), since that has to be generated once on a machine with Gradle
installed. Any one teammate runs this once and commits the result:

```bash
gradle wrapper --gradle-version 9.7.1
git add gradlew gradlew.bat gradle/
git commit -m "chore: add gradle wrapper"
```

After that, everyone else just uses `./gradlew`, no local Gradle install
required.

## Run it

```bash
./gradlew run                              # LinuxAdapter (default)
./gradlew run -Dphoenix.adapter=android     # AndroidAdapter
./gradlew run -Dphoenix.adapter=esp32       # Esp32Adapter
```

Expected output (linux example):

```
=== Phoenix Core starting ===
[LinuxAdapter] Registering high-performance Linux node...
[Phoenix] Node registered: id=node-linux-01 class=Linux cpuCores=8 memoryMb=16384
[Phoenix] Submitting task: id=task-001 type=Monte-Carlo-Pi
[LinuxAdapter] Executing task natively: task-001
[LinuxAdapter] Result reported: SUCCESS
[Phoenix] Task complete: status=SUCCESS output=Computed Monte-Carlo pi ~= 3.14159
=== Phoenix Core shutting down ===
```

## Test it

```bash
./gradlew test
```

`PhoenixModuleTest` mechanically proves the swap works (linux/android/esp32
+ a rejected unknown adapter name). This is the artifact to show your guide
instead of just eyeballing console output — it's a repeatable, automated
proof of the IoC claim.

## Project layout

```
com.phoenix
├── App.java              # entry point — only knows IHardwareAdapter
├── model/                 # NodeInfo, ResourceStatus, TaskDescriptor, TaskResult
├── adapter/
│   ├── IHardwareAdapter.java   # the golden interface
│   └── impl/
│       ├── LinuxAdapter.java
│       ├── AndroidAdapter.java
│       └── Esp32Adapter.java
└── core/
    └── PhoenixModule.java # Guice module + MapBinder-based adapter registry
```

## Day 1 milestone checklist

- [x] Domain models compile (`model/`)
- [x] `IHardwareAdapter` contract defined
- [x] Three reference (fake) adapters implemented
- [x] Guice wires an adapter into Core without Core knowing the concrete type
- [x] Swapping adapters requires zero source changes (`-Dphoenix.adapter=...`)
- [x] Automated test proves the swap (`PhoenixModuleTest`)
- [ ] Device Registry (Day 2)
- [ ] Weighted-cost Scheduler (Day 2)
- [ ] Dispatcher wiring Scheduler → Plugin Manager → Adapter (Day 2)
- [ ] Fourth adapter added with zero Core changes, as a live demo (Day 3)
- [ ] SQLite persistence (Day 4)
- [ ] Core ↔ mock node over MQTT (Day 4)

## Suggested team split for Days 2–4

- **Core architecture + Guice** — owns `core/`, keeps the plugin registry
  clean as it grows into the real "IoC Plugin Manager".
- **Scheduler** — owns the weighted-cost node selection
  (`w1*execTime + w2*energy + w3*load + w4*latency`, see Design doc Sec. 7).
- **Adapters** — gets one *real* adapter working end-to-end (Linux is
  easiest: real `/proc` stats instead of hardcoded numbers).
- **Infrastructure** — SQLite schema + MQTT topics, once the interfaces
  above have stabilized (don't start this in parallel with Core changes).

## What NOT to build this week

Real Android/ESP32 execution, JIVE integration, the SQL pattern-detection
pipeline, and k3s/k0s/Oakestra benchmarking are all S7/S8 milestones per
the project timeline (Table 1 in the Design doc) — not Day 1–4 work.
