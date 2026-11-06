import { test } from 'node:test';
import assert from 'node:assert';
import {
  clampSpeed,
  clampPresetSpeed,
  formatPace,
  formatPaceMile,
  encodeRscPacket,
  PaceTransitionManager,
} from './pacecast-logic';

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
