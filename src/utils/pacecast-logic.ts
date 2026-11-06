export const MIN_SPEED_KMH = 0.0;
export const MAX_SPEED_KMH = 30.0;
export const MAX_PRESET_SPEED_KMH = 24.0;
export const DEFAULT_SPEED_KMH = 16.8;
export const DEFAULT_PREVIOUS_SPEED_KMH = 6.0;
export const DEFAULT_SPEED_PRESETS = [5.0, 8.0, 10.0, 12.0, 15.0, 16.8];

export type UnitSystem = 'metric' | 'imperial';

export const KMH_TO_MPH = 0.621371192;
export const MPH_TO_KMH = 1.609344;

export function kmhToMph(kmh: number): number {
  return kmh * KMH_TO_MPH;
}

export function mphToKmh(mph: number): number {
  return mph * MPH_TO_KMH;
}

export interface RscPacket {
  speedKmh: number;
  speedMs: number;
  rawLittleEndianSpeed: number;
  hexPayload: string;
  bytes: number[];
  cadenceRpm: number;
}

export function clampSpeed(speed: number): number {
  const rounded = Math.round(speed * 10) / 10;
  return Math.min(Math.max(rounded, MIN_SPEED_KMH), MAX_SPEED_KMH);
}

export function clampPresetSpeed(speed: number): number {
  const rounded = Math.round(speed * 10) / 10;
  return Math.min(Math.max(rounded, 0.0), MAX_PRESET_SPEED_KMH);
}

export function formatPace(speedKmh: number): string {
  if (speedKmh <= 0.05) return '--:--';
  const totalSeconds = Math.round(3600 / speedKmh);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export function formatPaceMile(speedKmh: number): string {
  if (speedKmh <= 0.05) return '--:--';
  const speedMph = kmhToMph(speedKmh);
  if (speedMph <= 0.05) return '--:--';
  const totalSeconds = Math.round(3600 / speedMph);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export function encodeRscPacket(speedKmh: number, cadenceRpm: number = 0): RscPacket {
  const clampedSpeed = clampSpeed(speedKmh);
  const speedMs = clampedSpeed / 3.6;
  const rawLittleEndianSpeed = Math.min(Math.round(speedMs * 256), 65535);

  const byte0 = 0x00; // Flags: No stride length, no total distance
  const byte1 = rawLittleEndianSpeed & 0xff;
  const byte2 = (rawLittleEndianSpeed >> 8) & 0xff;
  const byte3 = cadenceRpm & 0xff;

  const bytes = [byte0, byte1, byte2, byte3];
  const hexPayload = bytes
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    .join(' ');

  return {
    speedKmh: clampedSpeed,
    speedMs,
    rawLittleEndianSpeed,
    hexPayload,
    bytes,
    cadenceRpm,
  };
}

export class PaceTransitionManager {
  private currentSpeed: number;
  private previousSpeed: number;
  private debounceMs: number;
  private timer: number | null = null;
  private isBurstActive: boolean = false;
  private onChange: (current: number, previous: number) => void;

  constructor(
    initialSpeed: number,
    initialPreviousSpeed: number,
    debounceMs: number = 3000,
    onChange: (current: number, previous: number) => void = () => {}
  ) {
    this.currentSpeed = clampSpeed(initialSpeed);
    this.previousSpeed = clampSpeed(initialPreviousSpeed);
    this.debounceMs = debounceMs;
    this.onChange = onChange;
  }

  public adjustBy(delta: number): { current: number; previous: number } {
    if (!this.isBurstActive) {
      this.previousSpeed = this.currentSpeed;
      this.isBurstActive = true;
    }

    if (this.timer !== null) {
      clearTimeout(this.timer);
    }

    this.timer = setTimeout(() => {
      this.isBurstActive = false;
      this.timer = null;
    }, this.debounceMs) as unknown as number;

    this.currentSpeed = clampSpeed(this.currentSpeed + delta);
    this.onChange(this.currentSpeed, this.previousSpeed);
    return { current: this.currentSpeed, previous: this.previousSpeed };
  }

  public swapPaces(): { current: number; previous: number } {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.isBurstActive = false;

    const temp = this.currentSpeed;
    this.currentSpeed = this.previousSpeed;
    this.previousSpeed = temp;

    this.onChange(this.currentSpeed, this.previousSpeed);
    return { current: this.currentSpeed, previous: this.previousSpeed };
  }

  public submitExact(newSpeed: number): { current: number; previous: number } {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.isBurstActive = false;

    this.previousSpeed = this.currentSpeed;
    this.currentSpeed = clampSpeed(newSpeed);

    this.onChange(this.currentSpeed, this.previousSpeed);
    return { current: this.currentSpeed, previous: this.previousSpeed };
  }

  public getState(): { current: number; previous: number } {
    return { current: this.currentSpeed, previous: this.previousSpeed };
  }
}
