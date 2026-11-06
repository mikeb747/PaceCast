# PaceCast - Native Android BLE Foot Pod (Garmin Forerunner 255)

PaceCast transforms your Android phone into a high-precision Bluetooth Low Energy (BLE) Running Speed & Cadence (RSC) foot pod for your Garmin Forerunner 255 (and any other BLE-compatible sports watch or bike computer).

## Architecture & Features

1. **Bluetooth GATT Server Specification:**
   - **Service UUID:** `0x1814` (Running Speed and Cadence)
   - **RSC Feature (`0x2A54`):** Read-only returning `[0x00, 0x00]` (instantaneous speed & cadence supported).
   - **RSC Measurement (`0x2A53`):** Notify property with Client Characteristic Configuration Descriptor (CCCD `0x2902`). Handled with immediate `BluetoothGatt.GATT_SUCCESS`.
   - **1 Hz Broadcast Loop:**
     - Speed conversion: `speedMs = currentPaceKmh / 3.6f`
     - Little-Endian unsigned 16-bit integer: `rawSpeed = round(speedMs * 256)`
     - 4-Byte payload: `[0x00, Speed LSB, Speed MSB, 0x00]`
2. **Tactile Brutalist UI:**
   - Theme: Dark charcoal background (`#3A3D40` outer, `#181A1B` main card), mint green accent (`#4ADE80`).
   - Tactile 3D bottom-bordered buttons with real physical depression effects.
   - Long-press acceleration: hold `-` or `+` to increment by `0.5 km/h` every 190ms.
   - 3-second debounced Previous Pace capture: guarantees recovery pace is preserved when adjusting speed.
   - Immediate Interval Pace Swap button.
   - Keep screen awake (`FLAG_KEEP_SCREEN_ON`) on treadmill consoles.

## How to Build & Run in Android Studio

1. Open Android Studio (Hedgehog or newer recommended).
2. Select **Open** and choose the `android` folder.
3. Allow Gradle to sync dependencies.
4. Connect an Android phone (API 26+) via USB with USB Debugging enabled.
5. Click **Run** (`Shift + F10`).
6. On your **Garmin Forerunner 255**:
   - Go to **Settings > Sensors & Accessories > Add New > FP - Foot Pod**.
   - Select **PaceCast**.
   - Start an Indoor Run or Treadmill activity! Speed and pace update with zero lag.
