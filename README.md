# OBD-C

OBD-C is a car assistant and real-time analysis platform built around a
custom OBD-II Bluetooth dongle. A family (or a car-rental fleet) shares one
or more cars; the app tracks who's driving and where, splits fuel cost, and
surfaces real-time and historical vehicle data collected by the hardware.

## Features

- Division of fuel cost among multiple users
- Real-time vehicle diagnostics and driving-behavior feedback
- Fuel efficiency tracking, maintenance reminders and alerts
- Trip history and last-parked location
- Compatibility with a wide range of vehicles that support OBD-II

## Repository layout

This is a monorepo with three buildable pieces plus the hardware design:

| Path | What it is | Start here |
|---|---|---|
| `app/` | Flutter app (iOS/Android/desktop) — the user-facing client | [`app/obd_app/README.md`](app/obd_app/README.md) |
| `api/` | Spring Boot backend — auth, cars, groups, trips, telemetry | [`api/README.md`](api/README.md) · [`api/ROADMAP.md`](api/ROADMAP.md) |
| `hardware/` | ESP32 firmware — BLE + OBD-II/CAN bus reading | [`hardware/README.md`](hardware/README.md) |
| `circuito/` | KiCad schematic/PCB for the OBD dongle | — |

## Architecture at a glance

The Flutter UI is a "dumb" reactive layer: it doesn't touch Bluetooth or
GPS, it just listens to an event channel and draws. A native foreground
service (Kotlin on Android, Swift on iOS) owns the BLE/GPS connection and
survives the UI being killed, so a trip keeps recording with the phone in a
pocket. The ESP32 dongle only speaks BLE, so **the phone is the relay** —
it batches readings and uploads them to the API, which is built to make
retrying a batch always safe. Full reasoning: [`docs/architecture.md`](docs/architecture.md).
System diagram (Structurizr C4 model): [`docs/C4 diagram`](<docs/C4 diagram>).

## Getting started

- **App** — Flutter setup: [`app/obd_app/README.md`](app/obd_app/README.md).
  Building for iOS needs one extra per-developer step:
  [`docs/ios-setup.md`](docs/ios-setup.md).
- **API** — needs Postgres and a couple of env vars:
  [`api/README.md`](api/README.md).
- **Firmware** — ESP-IDF via Docker/Dev Containers:
  [`hardware/README.md`](hardware/README.md).

## Development workflow

- **Branching**: GitFlow — see [`docs/flow.md`](docs/flow.md).
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/),
  enforced by a `commit-msg` hook (`feat`, `fix`, `docs`, `chore`, …).
- **One-time setup**: `make setup` installs the git hooks (lefthook +
  `.githooks`).
- **Before pushing**: `make format` (dart + clang-format) and `make check`
  (format check, `flutter analyze`, `flutter test`).
- CI (`.github/workflows/ci.yaml`) currently only guards that `README.md`
  and `docs/` exist; extend it here as the app/firmware test suites grow
  (the API already runs its own suite via `./mvnw test`, see
  [`api/README.md`](api/README.md#testing)).

## Docs index

| Doc | Contents |
|---|---|
| [`docs/flow.md`](docs/flow.md) | Git branching model (GitFlow) |
| [`docs/architecture.md`](docs/architecture.md) | Architecture decisions and rationale — background telemetry, native bridge, Pigeon, BLE pitfalls |
| [`docs/funcionalities.md`](docs/funcionalities.md) | Feature brainstorm/spec: sharing, historical data, notifications, B2B |
| [`docs/C4 diagram`](<docs/C4 diagram>) | C4 model (Structurizr) of the system |
| [`docs/ios-setup.md`](docs/ios-setup.md) | Per-developer iOS signing setup, and how to build/run on iOS |
