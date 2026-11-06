import React, { useState, useEffect, useRef } from 'react';
import {
  RotateCcw,
  Delete,
  CornerDownLeft,
  Radio,
  Sun,
  Code2,
  Activity,
  Layers,
  ArrowRight,
  Edit2,
  X,
  Check,
} from 'lucide-react';
import {
  clampSpeed,
  formatPace,
  encodeRscPacket,
  PaceTransitionManager,
  RscPacket,
  MIN_SPEED_KMH,
  MAX_SPEED_KMH,
  DEFAULT_SPEED_KMH,
  DEFAULT_PREVIOUS_SPEED_KMH,
  DEFAULT_SPEED_PRESETS,
  MAX_PRESET_SPEED_KMH,
  clampPresetSpeed,
} from '../utils/pacecast-logic';

const HOLD_DELAY = 450;
const HOLD_REPEAT = 190;

interface PaceControllerProps {
  onOpenTelemetry: () => void;
  onOpenAndroidCode: () => void;
  isBroadcasting: boolean;
  onToggleBroadcasting: () => void;
  onPacketEmitted: (packet: RscPacket) => void;
}

export const PaceController: React.FC<PaceControllerProps> = ({
  onOpenTelemetry,
  onOpenAndroidCode,
  isBroadcasting,
  onToggleBroadcasting,
  onPacketEmitted,
}) => {
  // Speed states
  const [speed, setSpeed] = useState<number>(() => {
    const saved = localStorage.getItem('pacecast-speed');
    return saved ? clampSpeed(Number(saved)) : DEFAULT_SPEED_KMH;
  });

  const [previousSpeed, setPreviousSpeed] = useState<number>(() => {
    const saved = localStorage.getItem('pacecast-previous-speed');
    return saved ? clampSpeed(Number(saved)) : DEFAULT_PREVIOUS_SPEED_KMH;
  });

  const [keypadValue, setKeypadValue] = useState<string>('');
  const [wakeLockActive, setWakeLockActive] = useState<boolean>(false);
  const wakeLockRef = useRef<any>(null);

  // Dynamic 6 Presets (stored in localStorage)
  const [presets, setPresets] = useState<number[]>(() => {
    const saved = localStorage.getItem('pacecast-presets');
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        if (Array.isArray(parsed) && parsed.length === 6) {
          return parsed.map((p) => clampPresetSpeed(Number(p)));
        }
      } catch {
        // fallback
      }
    }
    return DEFAULT_SPEED_PRESETS;
  });

  useEffect(() => {
    localStorage.setItem('pacecast-presets', JSON.stringify(presets));
  }, [presets]);

  // Preset long-press and editing modal state
  const [editingPresetIndex, setEditingPresetIndex] = useState<number | null>(null);
  const [presetEditBuffer, setPresetEditBuffer] = useState<string>('');
  const presetHoldTimeout = useRef<number | null>(null);
  const presetHoldFired = useRef<boolean>(false);

  function handlePresetPointerDown(index: number) {
    presetHoldFired.current = false;
    if (presetHoldTimeout.current !== null) {
      window.clearTimeout(presetHoldTimeout.current);
    }
    // Exactly 1 second long-press
    presetHoldTimeout.current = window.setTimeout(() => {
      presetHoldFired.current = true;
      if (typeof navigator !== 'undefined' && navigator.vibrate) {
        navigator.vibrate(60);
      }
      openEditPreset(index);
    }, 1000);
  }

  function handlePresetPointerUp(presetSpeed: number) {
    if (presetHoldTimeout.current !== null) {
      window.clearTimeout(presetHoldTimeout.current);
      presetHoldTimeout.current = null;
    }
    if (!presetHoldFired.current) {
      choosePreset(presetSpeed);
    }
  }

  function handlePresetPointerCancel() {
    if (presetHoldTimeout.current !== null) {
      window.clearTimeout(presetHoldTimeout.current);
      presetHoldTimeout.current = null;
    }
  }

  function openEditPreset(index: number) {
    setEditingPresetIndex(index);
    setPresetEditBuffer(presets[index].toFixed(1));
  }

  function closeEditPreset() {
    setEditingPresetIndex(null);
    setPresetEditBuffer('');
  }

  function handlePresetKeypadPress(key: string) {
    if (key === 'backspace') {
      setPresetEditBuffer((prev) => prev.slice(0, -1));
      return;
    }

    setPresetEditBuffer((current) => {
      if (key === '.' && current.includes('.')) return current;
      if (current.includes('.') && current.split('.')[1].length >= 1) return current;

      const next = current === '0' && key !== '.' ? key : current + key;
      const num = Number(next);
      if (Number.isFinite(num) && num > MAX_PRESET_SPEED_KMH) {
        return current;
      }
      return next.slice(0, 4);
    });
  }

  function handlePresetNudge(delta: number) {
    const current = Number(presetEditBuffer) || 0;
    const next = clampPresetSpeed(current + delta);
    setPresetEditBuffer(next.toFixed(1));
  }

  function saveEditedPreset() {
    if (editingPresetIndex === null) return;
    const num = Number(presetEditBuffer);
    if (!Number.isFinite(num)) return;
    const clamped = clampPresetSpeed(num);

    setPresets((prev) => {
      const copy = [...prev];
      copy[editingPresetIndex] = clamped;
      return copy;
    });
    closeEditPreset();
  }

  // PaceTransitionManager with 3-second debounce logic
  const transitionManagerRef = useRef<PaceTransitionManager | null>(null);
  if (!transitionManagerRef.current) {
    transitionManagerRef.current = new PaceTransitionManager(
      speed,
      previousSpeed,
      3000,
      (current, prev) => {
        setSpeed(current);
        setPreviousSpeed(prev);
      }
    );
  }

  // Hold / long-press timers for +/- buttons
  const holdTimeout = useRef<number | null>(null);
  const holdInterval = useRef<number | null>(null);
  const speedRef = useRef(speed);

  // Sync state to localStorage & manager
  useEffect(() => {
    speedRef.current = speed;
    localStorage.setItem('pacecast-speed', speed.toFixed(1));
  }, [speed]);

  useEffect(() => {
    localStorage.setItem('pacecast-previous-speed', previousSpeed.toFixed(1));
  }, [previousSpeed]);

  // 1 Hz BLE Broadcast Loop
  useEffect(() => {
    let timer: number | null = null;
    if (isBroadcasting) {
      // Immediate broadcast
      const initialPacket = encodeRscPacket(speedRef.current);
      onPacketEmitted(initialPacket);

      // 1000ms loop
      timer = window.setInterval(() => {
        const packet = encodeRscPacket(speedRef.current);
        onPacketEmitted(packet);
      }, 1000);
    }
    return () => {
      if (timer !== null) clearInterval(timer);
    };
  }, [isBroadcasting, onPacketEmitted]);

  // Cleanup hold timers on unmount
  useEffect(() => {
    return () => stopHold();
  }, []);

  // Screen Wake Lock (Web Screen Wake Lock API)
  const toggleWakeLock = async () => {
    if ('wakeLock' in navigator) {
      if (wakeLockActive) {
        try {
          await wakeLockRef.current?.release();
          wakeLockRef.current = null;
          setWakeLockActive(false);
        } catch (e) {
          console.warn('Wake lock release error', e);
        }
      } else {
        try {
          const lock = await (navigator as any).wakeLock.request('screen');
          wakeLockRef.current = lock;
          setWakeLockActive(true);
          lock.addEventListener('release', () => {
            setWakeLockActive(false);
          });
        } catch (err) {
          console.warn('Wake lock error', err);
        }
      }
    } else {
      // Mock toggle if unsupported
      setWakeLockActive(!wakeLockActive);
    }
  };

  // Adjuster button methods
  function stopHold() {
    if (holdTimeout.current !== null) {
      window.clearTimeout(holdTimeout.current);
      holdTimeout.current = null;
    }
    if (holdInterval.current !== null) {
      window.clearInterval(holdInterval.current);
      holdInterval.current = null;
    }
  }

  function startHold(direction: -1 | 1) {
    stopHold();
    setKeypadValue('');
    // First single tap ±0.1
    const res = transitionManagerRef.current?.adjustBy(direction * 0.1);
    if (res) {
      setSpeed(res.current);
      setPreviousSpeed(res.previous);
    }

    // Long press acceleration ±0.5
    holdTimeout.current = window.setTimeout(() => {
      const resTick = transitionManagerRef.current?.adjustBy(direction * 0.5);
      if (resTick) {
        setSpeed(resTick.current);
        setPreviousSpeed(resTick.previous);
      }

      holdInterval.current = window.setInterval(() => {
        const resInterval = transitionManagerRef.current?.adjustBy(direction * 0.5);
        if (resInterval) {
          setSpeed(resInterval.current);
          setPreviousSpeed(resInterval.previous);
        }
      }, HOLD_REPEAT);
    }, HOLD_DELAY);
  }

  // Keypad methods
  function pressKey(key: string) {
    if (key === 'backspace') {
      setKeypadValue((val) => val.slice(0, -1));
      return;
    }

    setKeypadValue((current) => {
      if (key === '.' && current.includes('.')) return current;
      if (current.includes('.') && current.split('.')[1].length >= 1) return current;
      const next = current === '0' && key !== '.' ? key : current + key;
      const numeric = Number(next);
      if (Number.isFinite(numeric) && numeric > MAX_SPEED_KMH) return current;
      return next.slice(0, 4);
    });
  }

  function applyKeypad() {
    const parsed = Number(keypadValue);
    if (!Number.isFinite(parsed) || keypadValue === '') return;
    const res = transitionManagerRef.current?.submitExact(parsed);
    if (res) {
      setSpeed(res.current);
      setPreviousSpeed(res.previous);
    }
    setKeypadValue('');
  }

  function recallPrevious() {
    const res = transitionManagerRef.current?.swapPaces();
    if (res) {
      setSpeed(res.current);
      setPreviousSpeed(res.previous);
    }
    setKeypadValue('');
  }

  function choosePreset(presetSpeed: number) {
    const res = transitionManagerRef.current?.submitExact(presetSpeed);
    if (res) {
      setSpeed(res.current);
      setPreviousSpeed(res.previous);
    }
    setKeypadValue('');
  }

  const currentRsc = encodeRscPacket(speed);

  return (
    <div className="w-full max-w-xl mx-auto h-full flex flex-col justify-between overflow-hidden gap-1.5 sm:gap-2">
      {/* Top Header (shrink-0, intrinsic height) */}
      <header className="shrink-0 flex items-center justify-between pb-0.5 px-0.5">
          {/* Logo & Brand: Clean PaceCast title (15% larger: text-[28px]) */}
          <div>
            <h1 className="text-[28px] font-black tracking-tight text-white leading-tight">
              PaceCast
            </h1>
          </div>

          {/* Header Action Tools */}
          <div className="flex items-center gap-1.5">
            {/* Screen Wake Lock Button */}
            <button
              onClick={toggleWakeLock}
              className={`p-1.5 rounded-xl border transition ${
                wakeLockActive
                  ? 'bg-amber-400/15 border-amber-400/40 text-amber-300'
                  : 'bg-[#222527] border-[#2e3235] text-neutral-400 hover:text-white'
              }`}
              title={wakeLockActive ? 'Screen Awake: ON' : 'Keep Screen Awake on Treadmill'}
              aria-label="Toggle screen wake lock"
            >
              <Sun className="w-4 h-4" />
            </button>

            {/* BLE Telemetry Inspector Button */}
            <button
              onClick={onOpenTelemetry}
              className="p-1.5 rounded-xl bg-[#222527] border border-[#2e3235] text-neutral-400 hover:text-emerald-400 hover:border-emerald-500/30 transition cursor-pointer"
              title="Inspect live BLE 0x1814 RSC GATT telemetry"
              aria-label="Inspect BLE telemetry"
            >
              <Radio className="w-4 h-4" />
            </button>

            {/* Android Kotlin Code Studio Button */}
            <button
              onClick={onOpenAndroidCode}
              className="p-1.5 rounded-xl bg-[#222527] border border-[#2e3235] text-neutral-400 hover:text-emerald-400 hover:border-emerald-500/30 transition cursor-pointer"
              title="View Kotlin & Compose Android source code"
              aria-label="View Android source code"
            >
              <Code2 className="w-4 h-4" />
            </button>

            {/* Interactive Status Pill (15% larger text) */}
            <button
              onClick={onToggleBroadcasting}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full border transition select-none cursor-pointer ${
                isBroadcasting
                  ? 'bg-emerald-500/15 border-emerald-500/50 text-emerald-400 shadow-[0_0_12px_rgba(74,222,128,0.2)]'
                  : 'bg-[#222527] border-[#2e3235] text-neutral-400 hover:text-white'
              }`}
              title="Click to start/stop BLE Advertising"
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  isBroadcasting ? 'bg-[#4ade80] animate-pulse-dot' : 'bg-neutral-500'
                }`}
              />
              <span className="text-[13px] font-bold tracking-wide">
                {isBroadcasting ? 'Broadcasting' : 'Standby'}
              </span>
            </button>
          </div>
        </header>

        {/* Current Pace & Speed Card (shrink-0, intrinsic height, 15% larger typography) */}
        <section
          className="shrink-0 bg-[#222527] border border-[#2e3235] rounded-2xl p-2.5 sm:p-3.5 space-y-2 sm:space-y-2.5 shadow-inner"
          aria-label="Current pace and speed controller"
        >
          {/* Dual Metrics: Speed & Pace */}
          <div className="grid grid-cols-2 gap-3 items-center">
            {/* Speed readout */}
            <div className="text-center border-r border-[#2e3235] pr-2">
              <div className="flex items-baseline justify-center gap-1 font-mono">
                <span className="text-5xl sm:text-6xl font-black text-white tracking-tight">
                  {speed.toFixed(1)}
                </span>
                <span className="text-sm sm:text-base font-semibold text-neutral-400">km/h</span>
              </div>
              <p className="text-xs font-bold text-neutral-400 uppercase tracking-wider mt-0.5">
                Speed
              </p>
            </div>

            {/* Pace readout (/km) */}
            <div className="text-center pl-2">
              <div className="flex items-baseline justify-center gap-1 font-mono">
                <span className="text-5xl sm:text-6xl font-black text-[#4ade80] tracking-tight">
                  {formatPace(speed)}
                </span>
                <span className="text-sm sm:text-base font-semibold text-neutral-400">/km</span>
              </div>
              <p className="text-xs font-bold text-neutral-400 uppercase tracking-wider mt-0.5">
                Pace
              </p>
            </div>
          </div>

          {/* Adjuster Buttons: Brutalist 3D Tactile Buttons (h-[58px]) */}
          <div className="flex items-center justify-between gap-3 pt-0.5">
            {/* Decrement Button */}
            <button
              className="btn-3d-neutral flex-1 h-[58px] rounded-xl flex items-center justify-center text-4xl font-bold cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
              aria-label="Decrease speed"
              onPointerDown={() => startHold(-1)}
              onPointerUp={stopHold}
              onPointerCancel={stopHold}
              onPointerLeave={stopHold}
              onContextMenu={(e) => e.preventDefault()}
              disabled={speed <= MIN_SPEED_KMH}
            >
              −
            </button>

            {/* Copy indicator */}
            <div className="flex flex-col items-center justify-center px-2 select-none">
              <span className="text-sm font-black text-white">± 0.1</span>
              <span className="text-[11px] text-neutral-400 whitespace-nowrap">Hold for 0.5</span>
            </div>

            {/* Increment Button (Mint Green 3D) */}
            <button
              className="btn-3d-mint flex-1 h-[58px] rounded-xl flex items-center justify-center text-4xl font-black cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
              aria-label="Increase speed"
              onPointerDown={() => startHold(1)}
              onPointerUp={stopHold}
              onPointerCancel={stopHold}
              onPointerLeave={stopHold}
              onContextMenu={(e) => e.preventDefault()}
              disabled={speed >= MAX_SPEED_KMH}
            >
              +
            </button>
          </div>
        </section>

        {/* Quick Speed Presets Bar (shrink-0, intrinsic height, 52px buttons) */}
        <section className="shrink-0 space-y-1" aria-labelledby="presets-heading">
          <div className="flex items-center justify-between text-sm font-bold text-neutral-400 uppercase tracking-wider px-1">
            <span id="presets-heading" className="flex items-center gap-1.5">
              <Layers className="w-4 h-4 text-emerald-400" />
              Presets
            </span>
            <span className="text-xs text-neutral-400 font-normal">Press and hold to change</span>
          </div>
          <div className="grid grid-cols-6 gap-1.5">
            {presets.map((p, index) => {
              const isSelected = Math.abs(speed - p) < 0.05;
              return (
                <button
                  key={index}
                  onPointerDown={() => handlePresetPointerDown(index)}
                  onPointerUp={() => handlePresetPointerUp(p)}
                  onPointerCancel={handlePresetPointerCancel}
                  onPointerLeave={handlePresetPointerCancel}
                  onContextMenu={(e) => e.preventDefault()}
                  className={`h-[52px] rounded-lg font-mono text-sm sm:text-base font-bold border transition cursor-pointer flex flex-col items-center justify-center select-none active:scale-[0.98] ${
                    isSelected
                      ? 'bg-emerald-500 text-black border-emerald-400 shadow-[0_0_8px_rgba(74,222,128,0.3)]'
                      : 'bg-[#222527] text-neutral-300 border-[#2e3235] hover:border-neutral-500 hover:text-white'
                  }`}
                  aria-label={`Preset ${index + 1}: ${p} km/h. Press and hold 1s to change.`}
                >
                  <span>{p % 1 === 0 ? p.toFixed(0) : p.toFixed(1)}</span>
                </button>
              );
            })}
          </div>
        </section>

        {/* Previous Pace Card & Keypad Display Row (shrink-0, intrinsic height, 15% larger text) */}
        <div className="shrink-0 grid grid-cols-2 gap-2 sm:gap-3">
          {/* Previous Pace Swap Card */}
          <button
            onClick={recallPrevious}
            className="h-[56px] sm:h-[60px] flex items-center justify-between px-3 sm:px-3.5 rounded-xl bg-[#222527] border border-[#2e3235] hover:border-emerald-500/40 transition group cursor-pointer text-left"
            title="Tap to swap with Current Pace (Interval Mode)"
            aria-label={`Swap with previous pace of ${previousSpeed.toFixed(1)} km/h`}
          >
            <div>
              <span className="text-[11px] sm:text-xs font-bold text-neutral-400 block uppercase tracking-wider">
                Previous pace
              </span>
              <div className="flex items-baseline gap-1 font-mono mt-0.5">
                <span className="text-2xl font-bold text-white group-hover:text-emerald-400 transition">
                  {previousSpeed.toFixed(1)}
                </span>
                <span className="text-xs sm:text-sm text-neutral-400">km/h</span>
              </div>
            </div>
            <div className="w-8 h-8 rounded-lg bg-[#181a1b] border border-[#2e3235] flex items-center justify-center text-emerald-400 group-hover:rotate-180 transition duration-300">
              <RotateCcw className="w-4 h-4" />
            </div>
          </button>

          {/* Keypad Display Box */}
          <div className="h-[56px] sm:h-[60px] flex items-center justify-between px-3 sm:px-3.5 rounded-xl bg-[#141517] border border-[#2e3235] text-right font-mono">
            <span className="text-[11px] sm:text-xs text-neutral-500 uppercase tracking-wider font-sans">
              Typed
            </span>
            <div className="flex items-baseline gap-1">
              <span
                className={`text-2xl font-bold ${
                  keypadValue ? 'text-[#4ade80]' : 'text-neutral-500'
                }`}
              >
                {keypadValue || '—'}
              </span>
              <span className="text-xs sm:text-sm text-neutral-500">km/h</span>
            </div>
          </div>
        </div>

        {/* Exact Pace Numpad - Expands vertically to fill all remaining screen height with zero bottom space */}
        <section className="flex-1 min-h-0 flex flex-col gap-1.5 sm:gap-2 pb-0.5" aria-label="Exact pace numpad">
          {/* 4 Equal-height rows for number keys (weight flex-[4]) */}
          <div className="flex-[4] min-h-0 grid grid-cols-3 grid-rows-4 gap-1.5 sm:gap-2">
            {['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0'].map((digit) => (
              <button
                key={digit}
                onClick={() => pressKey(digit)}
                className="btn-3d-keypad h-full w-full rounded-xl text-2xl font-bold font-mono cursor-pointer flex items-center justify-center active:scale-[0.99]"
                aria-label={`Keypad digit ${digit}`}
              >
                {digit}
              </button>
            ))}

            {/* Backspace Key */}
            <button
              onClick={() => pressKey('backspace')}
              className="btn-3d-keypad h-full w-full rounded-xl flex items-center justify-center text-neutral-300 hover:text-red-400 cursor-pointer active:scale-[0.99]"
              aria-label="Delete last digit"
            >
              <Delete className="w-6 h-6" />
            </button>
          </div>

          {/* 1 Equal-height row for Enter Pace Button (weight flex-1) */}
          <div className="flex-1 min-h-0 flex">
            <button
              onClick={applyKeypad}
              disabled={keypadValue === ''}
              className={`w-full h-full rounded-xl font-bold flex items-center justify-center gap-2 text-base sm:text-lg uppercase tracking-wider transition cursor-pointer border ${
                keypadValue !== ''
                  ? 'bg-[#4ade80] text-[#052e16] border-[#16a34a] border-b-4 hover:brightness-105 active:translate-y-1 active:border-b-0'
                  : 'bg-[#222527] text-neutral-500 border-[#2e3235] cursor-not-allowed opacity-60'
              }`}
              aria-label="Enter typed pace"
            >
              <span>Enter pace</span>
              <CornerDownLeft className="w-5 h-5" />
            </button>
          </div>
        </section>

      {/* Preset Editor Modal (0.0 - 24.0 km/h with 0.1 increments) */}
      {editingPresetIndex !== null && (
        <div
          className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-3"
          role="dialog"
          aria-modal="true"
          aria-labelledby="edit-preset-title"
        >
          <div className="w-full max-w-sm bg-[#181a1b] border border-[#2e3235] rounded-2xl p-4 sm:p-5 space-y-3.5 shadow-2xl">
            {/* Header */}
            <div className="flex items-center justify-between pb-1 border-b border-[#2e3235]">
              <div>
                <h3 id="edit-preset-title" className="text-base font-bold text-white flex items-center gap-1.5">
                  <Edit2 className="w-4 h-4 text-emerald-400" />
                  Edit Preset #{editingPresetIndex + 1}
                </h3>
                <p className="text-[11px] text-neutral-400 mt-0.5">
                  Enter speed 0.0 – 24.0 km/h (0.1 increments)
                </p>
              </div>
              <button
                onClick={closeEditPreset}
                className="p-1.5 rounded-lg text-neutral-400 hover:text-white hover:bg-[#222527] transition cursor-pointer"
                aria-label="Cancel editing preset"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Target Speed Entry Box */}
            <div className="bg-[#141517] border border-[#2e3235] rounded-xl p-3 flex items-center justify-between font-mono">
              <div>
                <span className="text-[10px] text-neutral-400 uppercase tracking-wider block font-sans">
                  Target Speed
                </span>
                <div className="flex items-baseline gap-1 mt-0.5">
                  <span className={`text-3xl font-black ${presetEditBuffer ? 'text-[#4ade80]' : 'text-neutral-500'}`}>
                    {presetEditBuffer || '—'}
                  </span>
                  <span className="text-xs text-neutral-400 font-semibold">km/h</span>
                </div>
              </div>
              <div className="text-right">
                <span className="text-[10px] text-neutral-400 uppercase tracking-wider block font-sans">
                  Pace
                </span>
                <div className="text-sm font-bold text-neutral-300 mt-0.5">
                  {formatPace(Number(presetEditBuffer) || 0)}/km
                </div>
              </div>
            </div>

            {/* Quick Nudge Buttons */}
            <div className="grid grid-cols-4 gap-1.5 text-xs font-mono font-bold">
              {[-0.5, -0.1, 0.1, 0.5].map((delta) => (
                <button
                  key={delta}
                  onClick={() => handlePresetNudge(delta)}
                  className="h-8 rounded-lg bg-[#222527] border border-[#2e3235] text-neutral-300 hover:text-white hover:border-emerald-500/50 transition cursor-pointer flex items-center justify-center"
                >
                  {delta > 0 ? `+${delta}` : delta}
                </button>
              ))}
            </div>

            {/* 3x4 Keypad */}
            <div className="grid grid-cols-3 gap-1.5">
              {['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0'].map((digit) => (
                <button
                  key={digit}
                  onClick={() => handlePresetKeypadPress(digit)}
                  className="btn-3d-keypad h-11 rounded-xl text-base font-bold font-mono cursor-pointer"
                >
                  {digit}
                </button>
              ))}
              <button
                onClick={() => handlePresetKeypadPress('backspace')}
                className="btn-3d-keypad h-11 rounded-xl flex items-center justify-center text-neutral-300 hover:text-red-400 cursor-pointer"
                aria-label="Delete digit"
              >
                <Delete className="w-5 h-5" />
              </button>
            </div>

            {/* Save & Cancel Row */}
            <div className="flex items-center gap-2 pt-1">
              <button
                onClick={closeEditPreset}
                className="flex-1 h-11 rounded-xl bg-[#222527] border border-[#2e3235] text-neutral-300 hover:text-white font-bold text-xs uppercase tracking-wider transition cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={saveEditedPreset}
                disabled={!presetEditBuffer || isNaN(Number(presetEditBuffer))}
                className="flex-1 h-11 rounded-xl btn-3d-mint font-bold text-xs uppercase tracking-wider flex items-center justify-center gap-1.5 disabled:opacity-40 disabled:pointer-events-none cursor-pointer"
              >
                <Check className="w-4 h-4" />
                <span>Save Preset</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
