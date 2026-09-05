# Phoenix

### A Lightweight, IoC-Based Middleware and Orchestrator for Heterogeneous Legacy Edge Devices

Phoenix is a research prototype that explores lightweight orchestration of heterogeneous,
resource-constrained and legacy edge devices through hardware abstraction, modular
adapters, and Inversion of Control / Dependency Injection.

The project is being developed as part of B.Tech Computer Science and Engineering
Project Phase II at Amrita Vishwa Vidyapeetham.

---

## Project Status

Current status: **Prototype / Day-1 Core Skeleton**

The current prototype establishes:

- Phoenix Core project structure
- `IHardwareAdapter` hardware abstraction
- Linux, Android and ESP32 reference adapters
- Guice-based IoC / Dependency Injection
- Adapter selection through configuration
- Automated unit tests
- Gradle Wrapper-based reproducible builds

The distributed node registry, scheduler, MQTT communication, real hardware execution,
trace-based architectural validation and benchmarking are being developed incrementally.

---

## Architecture

At a high level:

```text
                    +----------------------+
                    |     Phoenix Core     |
                    |----------------------|
                    | Device Registry      |
                    | Scheduler            |
                    | IoC / DI             |
                    | Task Dispatcher      |
                    | Trace Logger         |
                    +----------+-----------+
                               |
                     IHardwareAdapter
                               |
              +----------------+----------------+
              |                |                |
        Linux Adapter    Android Adapter   ESP32 Adapter
              |                |                |
          Linux node       Android node       ESP32
```

The Core depends on the `IHardwareAdapter` abstraction rather than directly depending
on hardware-specific implementations.

---

## Repository Structure

```text
phoenix/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/phoenix/
│   │           ├── App.java
│   │           ├── adapter/
│   │           │   ├── IHardwareAdapter.java
│   │           │   └── impl/
│   │           │       ├── LinuxAdapter.java
│   │           │       ├── AndroidAdapter.java
│   │           │       └── Esp32Adapter.java
│   │           ├── core/
│   │           │   └── PhoenixModule.java
│   │           └── model/
│   │               ├── NodeInfo.java
│   │               ├── ResourceStatus.java
│   │               ├── TaskDescriptor.java
│   │               └── TaskResult.java
│   └── test/
│       └── java/
│           └── com/phoenix/
│               └── PhoenixModuleTest.java
└── README.md
```

> Note: A `build/` directory (compiled classes, distributions, reports, test results)
> is generated locally by Gradle and is intentionally excluded from version control.

---

## Getting Started

### Prerequisites

- Git
- JDK 21
- Linux/macOS/Windows with a terminal
- Internet access for the first Gradle dependency download

Gradle itself does **not** need to be installed globally. The repository includes the
Gradle Wrapper.

### Current development toolchain

| Tool           | Version |
| -------------- | ------- |
| Java           | 21      |
| Gradle Wrapper | 9.7.1   |
| Guice          | 7.0.0   |
| JUnit          | 5.x     |

> Note: The project design currently targets Java 17 for the eventual JIVE-based
> architectural-validation pipeline. The present Core prototype uses Java 21 while
> compatibility with JIVE is being verified.

---

## Clone the Repository

```bash
git clone https://github.com/Alverok/phoenix.git
cd phoenix
```

Check that the wrapper is executable on Linux/macOS:

```bash
chmod +x gradlew
```

Verify Java:

```bash
java -version
javac -version
```

Verify the project:

```bash
./gradlew --version
```

---

## Build the Project

Run:

```bash
./gradlew clean build
```

A successful build should end with:

```text
BUILD SUCCESSFUL
```

This command compiles the project and runs the test suite.

---

## Run Phoenix

The current Day-1 prototype allows the active hardware adapter to be selected through
the `phoenix.adapter` system property.

### Default: Linux

```bash
./gradlew run
```

### Android

```bash
./gradlew run -Dphoenix.adapter=android
```

### ESP32

```bash
./gradlew run -Dphoenix.adapter=esp32
```

These selections are currently reference adapter implementations used to demonstrate
the IoC/DI architecture. They do not yet represent actual remote Android or ESP32
task execution.

---

## Run Tests

```bash
./gradlew test
```

For a full verification:

```bash
./gradlew clean test
```

---

## Development Workflow

Before starting work:

```bash
git pull
./gradlew clean test
```

Create a branch:

```bash
git switch -c feature/<your-feature-name>
```

Example:

```bash
git switch -c feature/node-registry
```

After making changes:

```bash
./gradlew clean test
git status
git add .
git commit -m "feat: add node registry"
```

Push the branch:

```bash
git push -u origin feature/<your-feature-name>
```

Open a Pull Request rather than pushing directly to `main`.

When adding a new hardware target, prefer implementing a new `IHardwareAdapter` rather
than modifying Core scheduling logic.

---

## Planned Components

The project will progressively introduce:

- Device Registry
- Weighted-cost task scheduler
- Task Dispatcher
- MQTT-based node communication
- SQLite persistence
- REST / CLI gateway
- Linux node agent
- Android adapter
- ESP32 adapter
- Failure detection and task re-placement
- Execution tracing
- Architecture/design-pattern verification
- Benchmarking against lightweight orchestration baselines

---

## Team

Project: **Phoenix**

Guide: **Dr. Swaminathan Jayaraman**

Amrita Vishwa Vidyapeetham
B.Tech Computer Science and Engineering
Project Phase II — Academic Year 2026–2027

---

## License

TODO
