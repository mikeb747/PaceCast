import { test } from 'node:test';
import assert from 'node:assert';
import {
  clampSpeed,
  clampPresetSpeed,
  formatPace,
  formatPaceMile,
  formatPresetSpeed,
  getDisplayPresetSpeed,
  kmhToMph,
  roundToTenth,
  encodeRscPacket,
  PaceTransitionManager,
  DEFAULT_SPEED_PRESETS,
  computeDynamicIslandMetrics,
  computeHeaderScale,
} from './pacecast-logic';

test('DEFAULT_SPEED_PRESETS matches [6, 11, 11.5, 12, 16, 17]', () => {
  assert.deepStrictEqual(DEFAULT_SPEED_PRESETS, [6.0, 11.0, 11.5, 12.0, 16.0, 17.0]);
});

test('clampSpeed respects min and max limits and 1 decimal place precision', () => {
  assert.strictEqual(clampSpeed(16.84), 16.8);
  assert.strictEqual(clampSpeed(16.86), 16.9);
  assert.strictEqual(clampSpeed(-5), 0.0);
  assert.strictEqual(clampSpeed(45), 30.0);
});

test('clampPresetSpeed clamps to 0.0 - 24.0 km/h with 0.1 precision', () => {
  assert.strictEqual(clampPresetSpeed(12.34), 12.3);
  assert.strictEqual(clampPresetSpeed(-2), 0.0);
  assert.strictEqual(clampPresetSpeed(25.5), 24.0);
});

test('formatPace accurately calculates min:sec per kilometer', () => {
  assert.strictEqual(formatPace(0.0), '--:--');
  assert.strictEqual(formatPace(12.0), '5:00');
  assert.strictEqual(formatPace(15.0), '4:00');
  assert.strictEqual(formatPace(16.8), '3:34');
});

test('encodeRscPacket produces accurate 4-byte Little-Endian BLE RSC payload for Garmin 255', () => {
  const packet = encodeRscPacket(12.0);
  assert.strictEqual(packet.speedKmh, 12.0);
  assert.strictEqual(packet.bytes[0], 0x00);
  assert.strictEqual(packet.bytes[3], 0x00);
  assert.strictEqual(packet.bytes.length, 4);

  // 11.5 km/h = 3.194444... m/s -> * 256 = 817.777... -> 818 (3.1953125 m/s = 11.503125 km/h)
  const p115 = encodeRscPacket(11.5);
  assert.strictEqual(p115.rawLittleEndianSpeed, 818);
  assert.strictEqual(p115.hexPayload, '00 32 03 00');

  // 16.0 km/h = 4.444444... m/s -> * 256 = 1137.777... -> 1138 (4.4453125 m/s = 16.003125 km/h)
  const p160 = encodeRscPacket(16.0);
  assert.strictEqual(p160.rawLittleEndianSpeed, 1138);
  assert.strictEqual(p160.hexPayload, '00 72 04 00');
});

test('PaceTransitionManager debounces previous pace during rapid +/- adjustments', async () => {
  let latestCurrent = 0;
  let latestPrev = 0;

  const manager = new PaceTransitionManager(10.0, 6.0, 100, (cur, prev) => {
    latestCurrent = cur;
    latestPrev = prev;
  });

  manager.adjustBy(0.1);
  assert.strictEqual(latestCurrent, 10.1);
  assert.strictEqual(latestPrev, 10.0);

  manager.adjustBy(0.1);
  assert.strictEqual(latestCurrent, 10.2);
  assert.strictEqual(latestPrev, 10.0);

  await new Promise((resolve) => setTimeout(resolve, 150));

  manager.adjustBy(0.1);
  assert.strictEqual(latestCurrent, 10.3);
  assert.strictEqual(latestPrev, 10.2);
});

test('PaceTransitionManager swap and submit exact pace logic', () => {
  const manager = new PaceTransitionManager(12.0, 6.0);
  let res = manager.swapPaces();
  assert.strictEqual(res.current, 6.0);
  assert.strictEqual(res.previous, 12.0);

  res = manager.submitExact(15.0);
  assert.strictEqual(res.current, 15.0);
  assert.strictEqual(res.previous, 6.0);
});

test('formatPresetSpeed and getDisplayPresetSpeed round to nearest 0.1 mile (16 km -> 9.9, 16.1 km -> 10)', () => {
  assert.strictEqual(getDisplayPresetSpeed(16.0, 'imperial'), 9.9);
  assert.strictEqual(formatPresetSpeed(16.0, 'imperial'), '9.9');
  assert.strictEqual(getDisplayPresetSpeed(16.1, 'imperial'), 10.0);
  assert.strictEqual(formatPresetSpeed(16.1, 'imperial'), '10');
  assert.strictEqual(formatPresetSpeed(16.0, 'metric'), '16');
  assert.strictEqual(formatPresetSpeed(11.5, 'metric'), '11.5');
  assert.deepStrictEqual(
    DEFAULT_SPEED_PRESETS.map((p) => formatPresetSpeed(p, 'imperial')),
    ['3.7', '6.8', '7.1', '7.5', '9.9', '10.6']
  );
});

test('PaceTransitionManager.adjustBy steps by 0.1 miles when unitSystem is imperial', () => {
  const manager = new PaceTransitionManager(16.0, 12.0);
  // 16.0 km/h displays as 9.9 mph
  assert.strictEqual(roundToTenth(kmhToMph(manager.getState().current)), 9.9);

  // +0.1 miles -> 10.0 mph (16.1 km/h)
  let res = manager.adjustBy(0.1, 'imperial');
  assert.strictEqual(res.current, 16.1);
  assert.strictEqual(roundToTenth(kmhToMph(res.current)), 10.0);

  // +0.1 miles -> 10.1 mph (16.3 km/h)
  res = manager.adjustBy(0.1, 'imperial');
  assert.strictEqual(res.current, 16.3);
  assert.strictEqual(roundToTenth(kmhToMph(res.current)), 10.1);

  // -0.1 miles -> 10.0 mph (16.1 km/h)
  res = manager.adjustBy(-0.1, 'imperial');
  assert.strictEqual(res.current, 16.1);
  assert.strictEqual(roundToTenth(kmhToMph(res.current)), 10.0);
});

test('DynamicIslandGeometry covers full camera cutout on Xiaomi Redmi Note 10 Pro (2.75x, B=93)', () => {
  const m = computeDynamicIslandMetrics({
    holeRect: { left: 503, top: 0, right: 577, bottom: 93 },
    density: 2.75,
  });
  assert.strictEqual(m.mode, 'tight-cutout');
  assert.strictEqual(m.targetY, 12); // ~4.4dp below top edge (not jammed at 5px)
  assert.strictEqual(m.pillHeightPx, 87); // ~31.6dp consistent visual height
  assert.strictEqual(m.bottomPx, 99); // extends 6px past B=93 so lens bottom is fully covered
  assert.ok(m.targetY < (m.estLensTopPx ?? 0), 'top of pill covers estimated lens top (19px)');
  assert.ok(m.bottomPx >= 93, 'bottom of pill fully covers cutout bottom (93px)');
  assert.strictEqual(m.spacerWidthPx, 72);
});

test('DynamicIslandGeometry preserves verified alignment on Google Pixel 8 Pro (2.25x, B=113)', () => {
  const m = computeDynamicIslandMetrics({
    holeRect: { left: 462, top: 0, right: 545, bottom: 113 },
    density: 2.25,
  });
  assert.strictEqual(m.mode, 'tall-cutout');
  assert.strictEqual(m.targetY, 21);
  assert.strictEqual(m.pillHeightPx, 71);
  assert.strictEqual(m.bottomPx, 92);
  assert.strictEqual(m.centerYPx, 56);
  assert.strictEqual(m.spacerWidthPx, 78);
});

test('DynamicIslandGeometry scales proportionally on Pixel 8 Pro native 3.0x resolution', () => {
  const m = computeDynamicIslandMetrics({
    holeRect: { left: 616, top: 0, right: 728, bottom: 151 },
    density: 3.0,
  });
  assert.strictEqual(m.mode, 'tall-cutout');
  assert.strictEqual(m.pillHeightPx, 95); // 31.7dp
  assert.strictEqual(m.targetY, 28); // 9.3dp
  assert.strictEqual(m.bottomPx, 123);
  assert.strictEqual(m.centerYPx, 75);
});

test('DynamicIslandGeometry uses floating cutoutPath bounds directly when API 31+ provides lens circle', () => {
  const m = computeDynamicIslandMetrics({
    holeRect: { left: 503, top: 0, right: 577, bottom: 93 },
    cutoutPathBounds: { left: 509, top: 20, right: 571, bottom: 88 },
    density: 2.75,
  });
  assert.strictEqual(m.mode, 'cutout-path');
  assert.ok(m.targetY <= 14 && m.targetY >= 8);
  assert.ok(m.bottomPx >= 93);
  assert.strictEqual(m.pillHeightPx, 87);
});

test('DynamicIslandGeometry ignores full-screen or top-anchored cutoutPath and falls back safely', () => {
  // Top-anchored path (top == 0) should fall back to tight-cutout logic
  const topAnchored = computeDynamicIslandMetrics({
    holeRect: { left: 503, top: 0, right: 577, bottom: 93 },
    cutoutPathBounds: { left: 503, top: 0, right: 577, bottom: 93 },
    density: 2.75,
  });
  assert.strictEqual(topAnchored.mode, 'tight-cutout');
  assert.strictEqual(topAnchored.bottomPx, 99);

  // No cutout at all uses density-independent fallback
  const fallback = computeDynamicIslandMetrics({
    holeRect: null,
    density: 3.0,
  });
  assert.strictEqual(fallback.mode, 'fallback');
  assert.strictEqual(fallback.pillHeightPx, 95);
  assert.strictEqual(fallback.targetY, 18);
});

test('computeHeaderScale scales top bar ~20% smaller on Xiaomi Redmi Note 10 Pro (1080px @ 2.75x = 393dp)', () => {
  const h = computeHeaderScale({
    screenWidthPx: 1080,
    density: 2.75,
    holeRect: { left: 503, top: 0, right: 577, bottom: 93 },
  });
  assert.strictEqual(h.hasCenteredCutout, true);
  assert.ok(h.availableWingDp > 182 && h.availableWingDp < 183.5);
  assert.ok(h.scale >= 0.79 && h.scale <= 0.81, `Expected ~0.80x on Xiaomi, got ${h.scale}`);
  // Verify scaled left cluster (~190dp * scale) comfortably clears the 182.9dp cutout left edge
  assert.ok(190 * h.scale < h.availableWingDp - 16);
});

test('computeHeaderScale preserves balanced proportions with camera clearance on Pixel 8 Pro (2.25x & 3.0x)', () => {
  const pixel225 = computeHeaderScale({
    screenWidthPx: 1008,
    density: 2.25,
    holeRect: { left: 462, top: 0, right: 545, bottom: 113 },
  });
  const pixel300 = computeHeaderScale({
    screenWidthPx: 1344,
    density: 3.0,
    holeRect: { left: 616, top: 0, right: 728, bottom: 151 },
  });
  assert.strictEqual(pixel225.hasCenteredCutout, true);
  assert.ok(pixel225.scale >= 0.90 && pixel225.scale <= 0.92, `Expected ~0.91x on Pixel, got ${pixel225.scale}`);
  assert.ok(Math.abs(pixel225.scale - pixel300.scale) < 0.005, 'Pixel 2.25x and 3.0x should scale identically');
});

test('computeHeaderScale handles compact 360dp phones and wide no-cutout displays cleanly', () => {
  const compact = computeHeaderScale({
    screenWidthPx: 1080,
    density: 3.0,
    holeRect: { left: 500, top: 0, right: 580, bottom: 96 },
  });
  assert.ok(compact.scale >= 0.72 && compact.scale <= 0.74);

  const wideNoCutout = computeHeaderScale({
    screenWidthPx: 1800,
    density: 3.0,
    holeRect: null,
  });
  assert.strictEqual(wideNoCutout.hasCenteredCutout, false);
  assert.strictEqual(wideNoCutout.scale, 1.0);
});
