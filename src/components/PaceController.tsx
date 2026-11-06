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
} from '../utils/pacecast-logic';

const HOLD_DELAY = 450;
const HOLD_REPEAT = 190;
const SPEED_PRESETS = [5.0, 8.0, 10.0, 12.0, 15.0, 16.8];

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
    <div className="w-full max-w-xl mx-auto flex-1 flex flex-col justify-center py-1.5 sm:py-3 px-1 sm:px-2 space-y-2.5 sm:space-y-3">
      {/* Top Header */}
      <header className="flex items-center justify-between pb-0.5 px-0.5">
          {/* Logo & Brand: Clean PaceCast title */}
          <div>
            <h1 className="text-2xl font-black tracking-tight text-white">
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

            {/* Interactive Status Pill */}
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
              <span className="text-[11px] font-bold tracking-wide">
                {isBroadcasting ? 'Broadcasting' : 'Standby'}
              </span>
            </button>
          </div>
        </header>

        {/* Current Pace & Speed Card */}
        <section
          className="bg-[#222527] border border-[#2e3235] rounded-2xl p-3 sm:p-4 space-y-2.5 sm:space-y-3 shadow-inner"
          aria-label="Current pace and speed controller"
        >
          {/* Dual Metrics: Speed & Pace */}
          <div className="grid grid-cols-2 gap-3 items-center">
            {/* Speed readout */}
            <div className="text-center border-r border-[#2e3235] pr-2">
              <div className="flex items-baseline justify-center gap-1 font-mono">
                <span className="text-4xl sm:text-5xl font-black text-white tracking-tight">
                  {speed.toFixed(1)}
                </span>
                <span className="text-xs sm:text-sm font-semibold text-neutral-400">km/h</span>
              </div>
              <p className="text-[10px] font-bold text-neutral-400 uppercase tracking-wider mt-0.5">
                Speed
              </p>
            </div>

            {/* Pace readout (/km) */}
            <div className="text-center pl-2">
              <div className="flex items-baseline justify-center gap-1 font-mono">
                <span className="text-4xl sm:text-5xl font-black text-[#4ade80] tracking-tight">
                  {formatPace(speed)}
                </span>
                <span className="text-xs sm:text-sm font-semibold text-neutral-400">/km</span>
              </div>
              <p className="text-[10px] font-bold text-neutral-400 uppercase tracking-wider mt-0.5">
                Pace
              </p>
            </div>
          </div>

          {/* Adjuster Buttons: Brutalist 3D Tactile Buttons (20% taller: h-[62px]) */}
          <div className="flex items-center justify-between gap-3 pt-0.5">
            {/* Decrement Button */}
            <button
              className="btn-3d-neutral flex-1 h-[62px] rounded-xl flex items-center justify-center text-3xl font-bold cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
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
              <span className="text-xs font-black text-white">± 0.1</span>
              <span className="text-[9px] text-neutral-400 whitespace-nowrap">Hold for 0.5</span>
            </div>

            {/* Increment Button (Mint Green 3D, 20% taller: h-[62px]) */}
            <button
              className="btn-3d-mint flex-1 h-[62px] rounded-xl flex items-center justify-center text-3xl font-black cursor-pointer disabled:opacity-40 disabled:pointer-events-none"
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

        {/* Quick Speed Presets Bar (20% taller buttons: h-[44px]) */}
        <section className="space-y-1.5" aria-labelledby="presets-heading">
          <div className="flex items-center justify-between text-xs font-bold text-neutral-400 uppercase tracking-wider px-1">
            <span id="presets-heading" className="flex items-center gap-1.5">
              <Layers className="w-3.5 h-3.5 text-emerald-400" />
              Presets
            </span>
            <span className="text-[10px] text-neutral-500 font-normal">Fast Speed Select</span>
          </div>
          <div className="grid grid-cols-6 gap-1.5">
            {SPEED_PRESETS.map((p) => {
              const isSelected = Math.abs(speed - p) < 0.05;
              return (
                <button
                  key={p}
                  onClick={() => choosePreset(p)}
                  className={`h-[44px] rounded-lg font-mono text-xs sm:text-sm font-bold border transition cursor-pointer flex items-center justify-center ${
                    isSelected
                      ? 'bg-emerald-500 text-black border-emerald-400 shadow-[0_0_8px_rgba(74,222,128,0.3)]'
                      : 'bg-[#222527] text-neutral-300 border-[#2e3235] hover:border-neutral-500 hover:text-white'
                  }`}
                  aria-label={`Set speed to ${p} km/h`}
                >
                  {p % 1 === 0 ? p.toFixed(0) : p.toFixed(1)}
                </button>
              );
            })}
          </div>
        </section>

        {/* Previous Pace Card & Keypad Display Row (20% taller: h-[62px]) */}
        <div className="grid grid-cols-2 gap-2 sm:gap-3">
          {/* Previous Pace Swap Card */}
          <button
            onClick={recallPrevious}
            className="h-[62px] flex items-center justify-between px-3 sm:px-3.5 rounded-xl bg-[#222527] border border-[#2e3235] hover:border-emerald-500/40 transition group cursor-pointer text-left"
            title="Tap to swap with Current Pace (Interval Mode)"
            aria-label={`Swap with previous pace of ${previousSpeed.toFixed(1)} km/h`}
          >
            <div>
              <span className="text-[9px] sm:text-[10px] font-bold text-neutral-400 block uppercase tracking-wider">
                Previous pace
              </span>
              <div className="flex items-baseline gap-1 font-mono mt-0.5">
                <span className="text-xl font-bold text-white group-hover:text-emerald-400 transition">
                  {previousSpeed.toFixed(1)}
                </span>
                <span className="text-[11px] text-neutral-400">km/h</span>
              </div>
            </div>
            <div className="w-8 h-8 rounded-lg bg-[#181a1b] border border-[#2e3235] flex items-center justify-center text-emerald-400 group-hover:rotate-180 transition duration-300">
              <RotateCcw className="w-4 h-4" />
            </div>
          </button>

          {/* Keypad Display Box */}
          <div className="h-[62px] flex items-center justify-between px-3 sm:px-3.5 rounded-xl bg-[#141517] border border-[#2e3235] text-right font-mono">
            <span className="text-[9px] sm:text-[10px] text-neutral-500 uppercase tracking-wider font-sans">
              Typed
            </span>
            <div className="flex items-baseline gap-1">
              <span
                className={`text-xl font-bold ${
                  keypadValue ? 'text-[#4ade80]' : 'text-neutral-500'
                }`}
              >
                {keypadValue || '—'}
              </span>
              <span className="text-[11px] text-neutral-500">km/h</span>
            </div>
          </div>
        </div>

        {/* Exact Pace Numpad */}
        <section className="space-y-2" aria-label="Exact pace numpad">
          {/* 3x4 Grid (20% taller buttons: h-[58px]) */}
          <div className="grid grid-cols-3 gap-2">
            {['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0'].map((digit) => (
              <button
                key={digit}
                onClick={() => pressKey(digit)}
                className="btn-3d-keypad h-[58px] rounded-xl text-lg sm:text-xl font-bold font-mono cursor-pointer"
                aria-label={`Keypad digit ${digit}`}
              >
                {digit}
              </button>
            ))}

            {/* Backspace Key (20% taller: h-[58px]) */}
            <button
              onClick={() => pressKey('backspace')}
              className="btn-3d-keypad h-[58px] rounded-xl flex items-center justify-center text-neutral-300 hover:text-red-400 cursor-pointer"
              aria-label="Delete last digit"
            >
              <Delete className="w-5 h-5" />
            </button>
          </div>

          {/* Enter Pace Button (20% taller: h-[58px]) */}
          <button
            onClick={applyKeypad}
            disabled={keypadValue === ''}
            className={`w-full h-[58px] rounded-xl font-bold flex items-center justify-center gap-2 text-sm uppercase tracking-wider transition cursor-pointer border ${
              keypadValue !== ''
                ? 'bg-[#4ade80] text-[#052e16] border-[#16a34a] border-b-4 hover:brightness-105 active:translate-y-1 active:border-b-0'
                : 'bg-[#222527] text-neutral-500 border-[#2e3235] cursor-not-allowed opacity-60'
            }`}
            aria-label="Enter typed pace"
          >
            <span>Enter pace</span>
            <CornerDownLeft className="w-4 h-4" />
          </button>
        </section>
    </div>
  );
};
