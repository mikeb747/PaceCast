export const MIN_SPEED_KMH = 0.0;
export const MAX_SPEED_KMH = 30.0;
export const MAX_PRESET_SPEED_KMH = 24.0;
export const DEFAULT_SPEED_KMH = 16.8;
export const DEFAULT_PREVIOUS_SPEED_KMH = 6.0;
export const DEFAULT_SPEED_PRESETS = [6.0, 11.0, 11.5, 12.0, 16.0, 17.0];

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
  decodedSpeedMs: number;
  decodedSpeedKmh: number;
  totalDistanceMeters: number;
  totalDistanceDecimeters: number;
  hexPayload: string;
  bytes: number[];
  cadenceRpm: number;
}

export function roundToTenth(value: number): number {
  return Math.round(value * 10) / 10;
}

export function clampSpeed(speed: number): number {
  const rounded = roundToTenth(speed);
  return Math.min(Math.max(rounded, MIN_SPEED_KMH), MAX_SPEED_KMH);
}

export function clampPresetSpeed(speed: number): number {
  const rounded = roundToTenth(speed);
  return Math.min(Math.max(rounded, 0.0), MAX_PRESET_SPEED_KMH);
}

export function getDisplayPresetSpeed(
  presetKmh: number,
  unitSystem: UnitSystem = 'metric'
): number {
  if (unitSystem === 'imperial') {
    return roundToTenth(kmhToMph(presetKmh));
  }
  return roundToTenth(presetKmh);
}

export function formatPresetSpeed(
  presetKmh: number,
  unitSystem: UnitSystem = 'metric'
): string {
  const displayVal = getDisplayPresetSpeed(presetKmh, unitSystem);
  return Math.abs(displayVal - Math.round(displayVal)) < 1e-6
    ? Math.round(displayVal).toString()
    : displayVal.toFixed(1);
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

export const RSC_FLAG_TOTAL_DISTANCE_PRESENT = 0x02;

export function encodeRscPacket(
  speedKmh: number,
  totalDistanceMeters: number = 0,
  cadenceRpm: number = 0
): RscPacket {
  const clampedSpeed = clampSpeed(speedKmh);
  // Full 64-bit IEEE-754 floating point m/s (no intermediate rounding)
  const speedMs = clampedSpeed / 3.6;
  // Direct 1/256 m/s BLE RSC uint16 integer calculation for maximum accuracy
  const rawLittleEndianSpeed = Math.min(Math.max(Math.round((clampedSpeed * 256) / 3.6), 0), 65535);
  const decodedSpeedMs = rawLittleEndianSpeed / 256;
  const decodedSpeedKmh = decodedSpeedMs * 3.6;

  // Total distance in decimeters (0.1 m resolution), clamped to uint32 range
  const safeDistanceMeters = Math.max(0, totalDistanceMeters);
  const totalDistanceDecimeters = Math.min(
    Math.max(Math.round(safeDistanceMeters * 10), 0),
    0xffffffff
  );

  const byte0 = RSC_FLAG_TOTAL_DISTANCE_PRESENT; // Flags: Bit 1 = Total Distance Present (0x02)
  const byte1 = rawLittleEndianSpeed & 0xff;
  const byte2 = (rawLittleEndianSpeed >> 8) & 0xff;
  const byte3 = cadenceRpm & 0xff;
  const byte4 = totalDistanceDecimeters & 0xff;
  const byte5 = (totalDistanceDecimeters >>> 8) & 0xff;
  const byte6 = (totalDistanceDecimeters >>> 16) & 0xff;
  const byte7 = (totalDistanceDecimeters >>> 24) & 0xff;

  const bytes = [byte0, byte1, byte2, byte3, byte4, byte5, byte6, byte7];
  const hexPayload = bytes
    .map((b) => b.toString(16).padStart(2, '0').toUpperCase())
    .join(' ');

  return {
    speedKmh: clampedSpeed,
    speedMs,
    rawLittleEndianSpeed,
    decodedSpeedMs,
    decodedSpeedKmh,
    totalDistanceMeters: safeDistanceMeters,
    totalDistanceDecimeters,
    hexPayload,
    bytes,
    cadenceRpm,
  };
}

export class RscDistanceIntegrator {
  private totalDistanceMeters: number = 0;

  public reset(): void {
    this.totalDistanceMeters = 0;
  }

  public tick(speedKmh: number, elapsedSeconds: number): RscPacket {
    const clampedSpeed = clampSpeed(speedKmh);
    const dt = Math.max(0, elapsedSeconds);
    this.totalDistanceMeters += (clampedSpeed / 3.6) * dt;
    return encodeRscPacket(clampedSpeed, this.totalDistanceMeters);
  }
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

  public adjustBy(
    delta: number,
    unitSystem: UnitSystem = 'metric'
  ): { current: number; previous: number } {
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

    if (unitSystem === 'imperial') {
      const currentMph = roundToTenth(kmhToMph(this.currentSpeed));
      const targetMph = Math.max(0, roundToTenth(currentMph + delta));
      this.currentSpeed = clampSpeed(mphToKmh(targetMph));
    } else {
      this.currentSpeed = clampSpeed(this.currentSpeed + delta);
    }
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

export interface CutoutRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export type IslandLayoutMode =
  | 'cutout-path'
  | 'floating-rect'
  | 'tight-cutout'
  | 'tall-cutout'
  | 'fallback';

export interface DynamicIslandMetrics {
  pillHeightPx: number;
  spacerWidthPx: number;
  targetY: number;
  bottomPx: number;
  centerYPx: number;
  mode: IslandLayoutMode;
  estLensTopPx: number | null;
  estLensCenterYPx: number | null;
}

/**
 * Mirrors Android's DynamicIslandGeometry.compute(...) for fast deterministic verification
 * across device cutouts (Xiaomi/MIUI, Google Pixel, Samsung, OnePlus, floating cutoutPath, etc.).
 */
export function computeDynamicIslandMetrics(params: {
  holeRect: CutoutRect | null;
  cutoutPathBounds?: CutoutRect | null;
  density: number;
}): DynamicIslandMetrics {
  const {
    holeRect,
    cutoutPathBounds = null,
    density,
  } = params;

  const safeDensity = Math.max(density, 1);
  const dpToPx = (dp: number) => Math.round(dp * safeDensity);
  const clamp = (val: number, min: number, max: number) => Math.min(Math.max(val, min), max);

  const MIN_VISUAL_HEIGHT_DP = 31.5;
  const MIN_PILL_HEIGHT_DP = 20;
  const MAX_PILL_HEIGHT_DP = 64;
  const MIN_TOP_MARGIN_DP = 3;
  const PATH_VERTICAL_PAD_DP = 2;
  const TIGHT_BOTTOM_PAD_DP = 2;
  const TALL_CUTOUT_THRESHOLD_DP = 42;

  const minVisualHeightPx = dpToPx(MIN_VISUAL_HEIGHT_DP);
  const minPillHeightPx = dpToPx(MIN_PILL_HEIGHT_DP);
  const maxPillHeightPx = dpToPx(MAX_PILL_HEIGHT_DP);
  const minTopMarginPx = dpToPx(MIN_TOP_MARGIN_DP);

  const rectWidth = (r: CutoutRect | null) => (r ? r.right - r.left : 0);
  const rectHeight = (r: CutoutRect | null) => (r ? r.bottom - r.top : 0);

  const holeWidth = rectWidth(holeRect) || rectWidth(cutoutPathBounds);
  const spacerWidthPx =
    holeWidth > 0 ? Math.max(holeWidth - dpToPx(2), dpToPx(26)) : dpToPx(28);

  const pathW = rectWidth(cutoutPathBounds);
  const pathH = rectHeight(cutoutPathBounds);
  const hasFloatingPathHole =
    cutoutPathBounds !== null &&
    cutoutPathBounds.top >= dpToPx(2) &&
    pathH >= dpToPx(10) &&
    pathH <= dpToPx(44) &&
    pathW >= dpToPx(10) &&
    pathW <= dpToPx(80) &&
    (holeRect === null || cutoutPathBounds.bottom <= holeRect.bottom + dpToPx(4));

  let basePillHeightPx: number;
  let baseTopY: number;
  let mode: IslandLayoutMode;
  let estLensTopPx: number | null;
  let estLensCenterYPx: number | null;

  if (hasFloatingPathHole && cutoutPathBounds !== null) {
    const padPx = dpToPx(PATH_VERTICAL_PAD_DP);
    const lensCenterY = (cutoutPathBounds.top + cutoutPathBounds.bottom) / 2;
    basePillHeightPx = Math.max(minVisualHeightPx, pathH + padPx * 2);
    baseTopY = Math.max(Math.round(lensCenterY - basePillHeightPx / 2), minTopMarginPx);
    mode = 'cutout-path';
    estLensTopPx = cutoutPathBounds.top;
    estLensCenterYPx = Math.round(lensCenterY);
  } else if (holeRect !== null && holeRect.top > 0 && holeRect.bottom > holeRect.top) {
    const padPx = dpToPx(PATH_VERTICAL_PAD_DP);
    const lensCenterY = (holeRect.top + holeRect.bottom) / 2;
    basePillHeightPx = Math.max(minVisualHeightPx, rectHeight(holeRect) + padPx * 2);
    baseTopY = Math.max(Math.round(lensCenterY - basePillHeightPx / 2), minTopMarginPx);
    mode = 'floating-rect';
    estLensTopPx = holeRect.top;
    estLensCenterYPx = Math.round(lensCenterY);
  } else if (holeRect !== null && holeRect.bottom > 0 && rectWidth(holeRect) > 0) {
    const w = rectWidth(holeRect);
    const bottomDp = holeRect.bottom / safeDensity;
    const computedEstLensTop = Math.max(0, holeRect.bottom - w);
    const computedEstLensCenterY = holeRect.bottom - Math.trunc(w / 2);
    estLensTopPx = computedEstLensTop;
    estLensCenterYPx = computedEstLensCenterY;

    if (bottomDp > TALL_CUTOUT_THRESHOLD_DP) {
      basePillHeightPx = minVisualHeightPx;
      const centerY = (holeRect.top + holeRect.bottom) >> 1;
      baseTopY = Math.max(centerY - Math.trunc(basePillHeightPx / 2), minTopMarginPx);
      mode = 'tall-cutout';
    } else {
      const bottomPadPx = dpToPx(TIGHT_BOTTOM_PAD_DP);
      const topPadPx = dpToPx(PATH_VERTICAL_PAD_DP);
      const desiredBottom = holeRect.bottom + bottomPadPx;
      const desiredTopCap = Math.max(computedEstLensTop - topPadPx, minTopMarginPx);
      const neededHeight = desiredBottom - desiredTopCap;
      basePillHeightPx = Math.max(minVisualHeightPx, neededHeight);
      baseTopY = Math.max(desiredBottom - basePillHeightPx, minTopMarginPx);
      mode = 'tight-cutout';
    }
  } else {
    basePillHeightPx = minVisualHeightPx;
    baseTopY = dpToPx(6);
    mode = 'fallback';
    estLensTopPx = null;
    estLensCenterYPx = null;
  }

  const pillHeightPx = clamp(basePillHeightPx, minPillHeightPx, maxPillHeightPx);
  const targetY = Math.max(baseTopY, 0);

  return {
    pillHeightPx,
    spacerWidthPx,
    targetY,
    bottomPx: targetY + pillHeightPx,
    centerYPx: targetY + Math.trunc(pillHeightPx / 2),
    mode,
    estLensTopPx,
    estLensCenterYPx,
  };
}

export interface HeaderScaleMetrics {
  scale: number;
  availableWingDp: number;
  safeWingDp: number;
  hasCenteredCutout: boolean;
}

/**
 * Mirrors Android's DynamicIslandGeometry.computeHeaderScale(...) for deterministic testing
 * of in-app top bar scaling across Xiaomi, Pixel, compact phones, and wide displays.
 */
export function computeHeaderScale(params: {
  screenWidthPx: number;
  density: number;
  holeRect: CutoutRect | null;
}): HeaderScaleMetrics {
  const { screenWidthPx, density, holeRect } = params;
  const safeDensity = Math.max(density, 1);
  const screenWidthDp = Math.max(screenWidthPx / safeDensity, 320);
  const effectiveContainerDp = Math.min(screenWidthDp, 520);

  const holeWidth = holeRect ? holeRect.right - holeRect.left : 0;
  const holeCenterX = holeRect ? (holeRect.left + holeRect.right) >> 1 : -1;
  const isCenteredCutout =
    holeRect !== null &&
    holeWidth > 0 &&
    screenWidthPx > 0 &&
    holeCenterX >= Math.round(screenWidthPx * 0.3) &&
    holeCenterX <= Math.round(screenWidthPx * 0.7);

  let rawWingDp: number;
  if (isCenteredCutout && holeRect !== null) {
    const leftWingDp = holeRect.left / safeDensity;
    const rightWingDp = (screenWidthPx - holeRect.right) / safeDensity;
    rawWingDp = Math.min(Math.min(leftWingDp, rightWingDp), effectiveContainerDp * 0.5);
  } else {
    rawWingDp = effectiveContainerDp * 0.46;
  }

  const safeWingDp = Math.max(rawWingDp - 16, 110);
  const scale = Math.min(Math.max(safeWingDp / 208, 0.72), 1.0);

  return {
    scale,
    availableWingDp: rawWingDp,
    safeWingDp,
    hasCenteredCutout: isCenteredCutout,
  };
}
