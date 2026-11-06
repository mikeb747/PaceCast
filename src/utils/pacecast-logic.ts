/**
 * PaceCast Core BLE & Pace Transition Logic
 * Decoupled pure logic for unit testing and dual Web/Android implementations.
 */

export const MIN_SPEED_KMH = 0.0;
export const MAX_SPEED_KMH = 30.0;
export const DEFAULT_SPEED_KMH = 16.8;
export const DEFAULT_PREVIOUS_SPEED_KMH = 6.0;

export interface RscPacket {
  speedKmh: number;
  speedMs: number;
  rawSpeed: number; // 1/256 m/s
  bytes: [number, number, number, number];
  hexPayload: string;
  debugDescription: string;
}

export const DEFAULT_SPEED_PRESETS = [5.0, 8.0, 10.0, 12.0, 15.0, 16.8];
export const MIN_PRESET_SPEED_KMH = 0.0;
export const MAX_PRESET_SPEED_KMH = 24.0;

/**
 * Clamps speed to valid range and rounds to 1 decimal place.
 */
export function clampSpeed(value: number, min = MIN_SPEED_KMH, max = MAX_SPEED_KMH): number {
  if (isNaN(value)) return min;
  const clamped = Math.min(max, Math.max(min, value));
  return Math.round(clamped * 10) / 10;
}

/**
 * Clamps preset speed to 0.0 - 24.0 km/h with 0.1 increments.
 */
export function clampPresetSpeed(value: number): number {
  return clampSpeed(value, MIN_PRESET_SPEED_KMH, MAX_PRESET_SPEED_KMH);
}

export type UnitSystem = 'metric' | 'imperial';

export const KMH_TO_MPH = 0.621371192;
export const MPH_TO_KMH = 1.609344;

export function kmhToMph(kmh: number): number {
  return Math.round(kmh * KMH_TO_MPH * 10) / 10;
}

export function mphToKmh(mph: number): number {
  return Math.round(mph * MPH_TO_KMH * 10) / 10;
}

/**
 * Formats speed in km/h to running pace min:sec /km.
 * E.g. 12 km/h -> 5:00 /km, 16.8 km/h -> 3:34 /km
 */
export function formatPace(speedKmh: number): string {
  if (speedKmh <= 0) return "--:--";
  const totalSeconds = Math.round((3600 / speedKmh));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

/**
 * Formats speed in km/h to running pace min:sec /mile.
 * E.g. 16.8 km/h (10.4 mph) -> 5:45 /mi, 12 km/h (7.5 mph) -> 8:03 /mi
 */
export function formatPaceMile(speedKmh: number): string {
  if (speedKmh <= 0) return "--:--";
  const speedMph = speedKmh * KMH_TO_MPH;
  if (speedMph <= 0) return "--:--";
  const totalSeconds = Math.round(3600 / speedMph);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

/**
 * Converts km/h to Bluetooth Low Energy (BLE) Running Speed and Cadence (RSC)
 * 0x1814 Service / 0x2A53 Characteristic Little-Endian 4-byte payload.
 *
 * Spec:
 * - Speed is in units of 1/256 m/s, unsigned 16-bit integer (Little-Endian).
 * - Byte 0: Flags (0x00 = Instantaneous Stride Length Present: 0, Total Distance: 0)
 * - Byte 1: Instantaneous Speed LSB
 * - Byte 2: Instantaneous Speed MSB
 * - Byte 3: Instantaneous Cadence RPM (0x00 or optional)
 */
export function encodeRscPacket(speedKmh: number, cadenceRpm = 0): RscPacket {
  const safeKmh = clampSpeed(speedKmh);
  const speedMs = safeKmh / 3.6;
  const rawSpeed = Math.min(65535, Math.max(0, Math.round(speedMs * 256)));

  const flags = 0x00;
  const speedLsb = rawSpeed & 0xff;
  const speedMsb = (rawSpeed >> 8) & 0xff;
  const cadence = Math.min(255, Math.max(0, Math.round(cadenceRpm)));

  const bytes: [number, number, number, number] = [flags, speedLsb, speedMsb, cadence];
  const hexPayload = bytes.map((b) => b.toString(16).padStart(2, "0").toUpperCase()).join(" ");

  return {
    speedKmh: safeKmh,
    speedMs: Math.round(speedMs * 1000) / 1000,
    rawSpeed,
    bytes,
    hexPayload,
    debugDescription: `Flags:0x00 Speed:0x${speedMsb.toString(16).padStart(2, "0")}${speedLsb.toString(16).padStart(2, "0")} (${rawSpeed}/256 m/s = ${speedMs.toFixed(2)}m/s) Cadence:${cadence}`,
  };
}

/**
 * Pace transition state manager with 3-second debounce rules.
 *
 * Requirements:
 * 1. Only update Previous Pace when a new speed is submitted via the Numpad "Enter pace" button,
 *    OR before the first tap of a "-" / "+" adjustment sequence (debounced by 3 seconds so tapping
 *    +0.1 multiple times doesn't overwrite the saved recovery pace with intermediate steps).
 * 2. Tapping Previous Pace card immediately swaps Current Pace and Previous Pace.
 */
export class PaceTransitionManager {
  private currentPace: number;
  private previousPace: number;
  private adjustmentActive = false;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private debounceMs: number;
  private onStateChange?: (current: number, previous: number) => void;

  constructor(
    initialCurrent = DEFAULT_SPEED_KMH,
    initialPrevious = DEFAULT_PREVIOUS_SPEED_KMH,
    debounceMs = 3000,
    onStateChange?: (current: number, previous: number) => void
  ) {
    this.currentPace = clampSpeed(initialCurrent);
    this.previousPace = clampSpeed(initialPrevious);
    this.debounceMs = debounceMs;
    this.onStateChange = onStateChange;
  }

  public getCurrent(): number {
    return this.currentPace;
  }

  public getPrevious(): number {
    return this.previousPace;
  }

  /**
   * Called when "-" or "+" is tapped or held.
   * If this is the start of an adjustment burst (adjustmentActive is false),
   * the current pace is captured into previousPace first.
   * Debounce timer is reset to 3000ms.
   */
  public adjustBy(delta: number): { current: number; previous: number } {
    if (!this.adjustmentActive) {
      // First tap of a sequence: capture current pace into previous pace
      this.previousPace = this.currentPace;
      this.adjustmentActive = true;
    }

    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer);
    }

    this.debounceTimer = setTimeout(() => {
      this.adjustmentActive = false;
      this.debounceTimer = null;
    }, this.debounceMs);

    this.currentPace = clampSpeed(this.currentPace + delta);
    this.notify();
    return { current: this.currentPace, previous: this.previousPace };
  }

  /**
   * Called when "Enter pace" is tapped on the numpad.
   * Moves Current Pace into Previous Pace, sets Current to new speed,
   * resets any pending adjustment burst.
   */
  public submitExact(newSpeed: number): { current: number; previous: number } {
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.adjustmentActive = false;

    const safeSpeed = clampSpeed(newSpeed);
    if (safeSpeed !== this.currentPace) {
      this.previousPace = this.currentPace;
      this.currentPace = safeSpeed;
      this.notify();
    }
    return { current: this.currentPace, previous: this.previousPace };
  }

  /**
   * Called when Previous Pace card is tapped.
   * Immediately swaps Current Pace and Previous Pace.
   */
  public swapPaces(): { current: number; previous: number } {
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.adjustmentActive = false;

    const temp = this.currentPace;
    this.currentPace = this.previousPace;
    this.previousPace = temp;
    this.notify();
    return { current: this.currentPace, previous: this.previousPace };
  }

  public resetAdjustmentDebounce(): void {
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    this.adjustmentActive = false;
  }

  private notify(): void {
    if (this.onStateChange) {
      this.onStateChange(this.currentPace, this.previousPace);
    }
  }
}
