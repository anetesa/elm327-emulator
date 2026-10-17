# ELM327 Emulator

Turns an Android phone into an **OBD-II adapter with a virtual car behind it**. Another phone runs
your scanner app and connects to it exactly as to a real ELM327 dongle — over **Bluetooth Classic
(SPP)**, **Bluetooth LE** or **Wi‑Fi (TCP)** — so you can test an OBD app end to end without a car,
without an adapter and without a cable.

## Why

Testing an OBD-II app usually means sitting in a car with a laptop. Real adapters also differ a lot:
genuine chips, cheap v1.5 clones, fake "v2.1" clones that ignore half of the protocol, slow BLE
dongles that send 20 bytes at a time. This emulator reproduces those behaviours on demand and runs
the real radio stacks of the phone, so the transport code of the app is tested too, not only its
parser.

## Features

- **Three transports at once**
  - Bluetooth Classic: RFCOMM server with the Serial Port Profile UUID, as all classic dongles.
  - Bluetooth LE: GATT service `FFF0` with notify `FFF1` and write `FFF2` (Vgate, Veepeak and clones);
    replies are split by MTU and sent one notification at a time.
  - Wi‑Fi: TCP server on port `35000`; works over the phone's own hotspot.
- **A faithful ELM327 command interpreter**
  - Echo, linefeeds, spaces and headers (`ATE`, `ATL`, `ATS`, `ATH`), `ATZ`/`ATWS`/`ATD`, `ATI`, `AT@1`,
    `ATRV`, `ATSP`/`ATTP`, `ATDP`/`ATDPN`, STN's `STI`.
  - Automatic protocol search (`SEARCHING...`), `UNABLE TO CONNECT` on a wrong manual protocol,
    `NO DATA`, `BUFFER FULL`, `?`.
  - ISO 15765-4 CAN with ISO-TP multi-frame replies (headers off: `014` / `0:` / `1:` lines; headers on:
    `7E8 10 14 …` frames with padding) and ISO 9141-2 K-Line (header `48 6B 10` and checksum).
- **A virtual car**
  - SAE J1979 services 01 (live data, readiness, warning lamp), 02 (freeze frame), 03/07/0A (stored,
    pending, permanent codes), 04 (clear), 06 (on-board monitor tests incl. misfire counters), 09 (VIN,
    calibration ids and CVN, in-use performance ratios, ECU name).
  - SAE J1979-2 (OBD on UDS): `22 F4xx`, `22 F802`, `19 42`, `19 55`, `14 FFFF33`.
  - Live values change over time: the engine warms up, idles and revs.
- **Adapter profiles**: genuine ELM327 v2.2, clone v1.5, fake v2.1 (single PID, rejected AT commands),
  STN1110, slow BLE (20-byte chunks), tiny buffer, unstable link that drops after 18 commands.
- **A live log** of every command and reply on the screen.

## Architecture

```
core/   pure Kotlin/JVM, no Android
  Elm327          command interpreter: settings, protocol search, OBD services, formatting
  Vehicle         the car: PIDs over time, codes, freeze frame, Mode 06 and Mode 09 data
  DemoVehicles    a ready-made petrol car (CAN, K-Line or OBD on UDS)
  AdapterProfile  how a given adapter behaves (delays, chunking, quirks)
  ElmSession      one client: bytes in → commands → delayed, chunked replies out
app/    Android
  ClassicServer / BleServer / TcpServer   one LinkServer per transport, all feeding ElmSession
  EmulatorViewModel, MainScreen           Jetpack Compose UI
```

The interpreter knows nothing about transports and the transports know nothing about OBD: each
server only moves bytes between its socket and an `ElmSession`. The core is covered by unit tests
(`./gradlew :core:test`).

## Usage

1. Install the app on a spare Android phone (Android 8+), open it and allow Bluetooth.
2. Pick an adapter profile and a car, keep the transports you need switched on, tap **Start**.
3. On the phone with your scanner app:
   - **Bluetooth Classic** — pair the two phones in the system settings, then pick the emulator in
     the scanner's list of paired devices.
   - **Bluetooth LE** — just scan from the scanner app. Many scanners only list devices whose names
     contain "OBD" or "ELM": tap **Rename Bluetooth to "OBDII Emulator"**.
   - **Wi‑Fi** — turn on the emulator phone's hotspot and join it, then enter one of the addresses
     the app shows, port 35000.

## Build

```
./gradlew :core:test :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (compile SDK 37).

## License

MIT
