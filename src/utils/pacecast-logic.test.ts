import test from 'node:test';
import assert from 'node:assert/strict';
import {
  clampSpeed,
  formatPace,
  encodeRscPacket,
  PaceTransitionManager,
} from './pacecast-logic.js';

test('clampSpeed respects min and max limits and 1 decimal place precision', () => {
  assert.equal(clampSpeed(-5), 0.0);
  assert.equal(clampSpeed(35), 30.0);
  assert.equal(clampSpeed(16.84), 16.8);
  assert.equal(clampSpeed(16.86), 16.9);
  assert.equal(clampSpeed(0.0), 0.0);
});

test('clampPresetSpeed clamps to 0.0 - 24.0 km/h with 0.1 precision', async () => {
  const { clampPresetSpeed } = await import('./pacecast-logic.js');
  assert.equal(clampPresetSpeed(-2), 0.0);
  assert.equal(clampPresetSpeed(25.5), 24.0);
  assert.equal(clampPresetSpeed(12.34), 12.3);
  assert.equal(clampPresetSpeed(23.96), 24.0);
});

test('formatPace accurately calculates min:sec per kilometer', () => {
  assert.equal(formatPace(0), '--:--');
  assert.equal(formatPace(6.0), '10:00');
  assert.equal(formatPace(10.0), '6:00');
  assert.equal(formatPace(12.0), '5:00');
  assert.equal(formatPace(16.8), '3:34');
  assert.equal(formatPace(20.0), '3:00');
});

test('encodeRscPacket produces accurate 4-byte Little-Endian BLE RSC payload for Garmin 255', () => {
  // Test with 16.8 km/h
  // speedMs = 16.8 / 3.6 = 4.666667 m/s
  // rawSpeed = round(4.666667 * 256) = 1195 (0x04AB)
  // LSB = 0xAB (171), MSB = 0x04 (4)
  const packet16_8 = encodeRscPacket(16.8);
  assert.equal(packet16_8.speedKmh, 16.8);
  assert.equal(packet16_8.rawSpeed, 1195);
  assert.equal(packet16_8.bytes[0], 0x00); // Flags
  assert.equal(packet16_8.bytes[1], 0xab); // Speed LSB
  assert.equal(packet16_8.bytes[2], 0x04); // Speed MSB
  assert.equal(packet16_8.bytes[3], 0x00); // Cadence
  assert.equal(packet16_8.hexPayload, '00 AB 04 00');

  // Test with 6.0 km/h
  // speedMs = 6.0 / 3.6 = 1.666667 m/s
  // rawSpeed = round(1.666667 * 256) = 427 (0x01AB)
  // LSB = 0xAB (171), MSB = 0x01 (1)
  const packet6_0 = encodeRscPacket(6.0);
  assert.equal(packet6_0.rawSpeed, 427);
  assert.equal(packet6_0.bytes[0], 0x00);
  assert.equal(packet6_0.bytes[1], 0xab);
  assert.equal(packet6_0.bytes[2], 0x01);
  assert.equal(packet6_0.bytes[3], 0x00);
  assert.equal(packet6_0.hexPayload, '00 AB 01 00');

  // Test with 0 km/h
  const packet0 = encodeRscPacket(0);
  assert.equal(packet0.rawSpeed, 0);
  assert.deepEqual(packet0.bytes, [0x00, 0x00, 0x00, 0x00]);
});

test('PaceTransitionManager debounces previous pace during rapid +/- adjustments', async () => {
  const manager = new PaceTransitionManager(16.8, 6.0, 50); // 50ms debounce for test

  assert.equal(manager.getCurrent(), 16.8);
  assert.equal(manager.getPrevious(), 6.0);

  // First tap of a sequence captures 16.8 into previousPace
  const r1 = manager.adjustBy(0.1);
  assert.equal(r1.current, 16.9);
  assert.equal(r1.previous, 16.8);

  // Subsequent taps within debounce window do NOT overwrite previousPace
  const r2 = manager.adjustBy(0.1);
  assert.equal(r2.current, 17.0);
  assert.equal(r2.previous, 16.8);

  const r3 = manager.adjustBy(0.5);
  assert.equal(r3.current, 17.5);
  assert.equal(r3.previous, 16.8);

  // Wait for debounce to settle
  await new Promise((resolve) => setTimeout(resolve, 80));

  // Now a new tap starts a new sequence, capturing current (17.5) into previous
  const r4 = manager.adjustBy(-0.1);
  assert.equal(r4.current, 17.4);
  assert.equal(r4.previous, 17.5);
});

test('PaceTransitionManager swap and submit exact pace logic', () => {
  const manager = new PaceTransitionManager(16.8, 6.0);

  // Instant swap
  const swapped = manager.swapPaces();
  assert.equal(swapped.current, 6.0);
  assert.equal(swapped.previous, 16.8);

  // Swap back
  const swappedBack = manager.swapPaces();
  assert.equal(swappedBack.current, 16.8);
  assert.equal(swappedBack.previous, 6.0);

  // Exact submit moves current (16.8) into previous and sets new pace
  const submitted = manager.submitExact(14.5);
  assert.equal(submitted.current, 14.5);
  assert.equal(submitted.previous, 16.8);
});
