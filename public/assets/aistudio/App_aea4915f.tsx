import { useEffect, useRef, useState } from "react";

const MIN_SPEED = 0;
const MAX_SPEED = 20;
const HOLD_DELAY = 450;
const HOLD_REPEAT = 190;
const PRESETS = [1, 2, 3, 4, 5, 6];

function clampSpeed(value: number) {
  return Math.min(MAX_SPEED, Math.max(MIN_SPEED, Math.round(value * 10) / 10));
}

function getStoredNumber(key: string, fallback: number) {
  const value = Number(localStorage.getItem(key));
  return Number.isFinite(value) ? clampSpeed(value) : fallback;
}

function formatPace(speed: number) {
  if (speed <= 0) return "--:--";
  const totalSeconds = Math.round((60 / speed) * 60);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

export default function App() {
  const [speed, setSpeed] = useState(() => getStoredNumber("pacecast-speed", 6));
  const [previousSpeed, setPreviousSpeed] = useState(() =>
    getStoredNumber("pacecast-previous-speed", 3),
  );
  const [keypadValue, setKeypadValue] = useState("");
  const holdTimeout = useRef<number | null>(null);
  const holdInterval = useRef<number | null>(null);
  const speedRef = useRef(speed);

  useEffect(() => {
    speedRef.current = speed;
    localStorage.setItem("pacecast-speed", speed.toFixed(1));
  }, [speed]);

  useEffect(() => {
    localStorage.setItem("pacecast-previous-speed", previousSpeed.toFixed(1));
  }, [previousSpeed]);

  useEffect(() => {
    return () => stopHold();
  }, []);

  function selectSpeed(next: number) {
    const safeNext = clampSpeed(next);
    const current = speedRef.current;

    if (safeNext === current) return;
    if (Math.abs(safeNext - current) >= 0.9) {
      setPreviousSpeed(current);
    }
    speedRef.current = safeNext;
    setSpeed(safeNext);
  }

  function nudge(direction: -1 | 1, amount: number) {
    selectSpeed(speedRef.current + direction * amount);
  }

  function stopHold() {
    if (holdTimeout.current !== null) window.clearTimeout(holdTimeout.current);
    if (holdInterval.current !== null) window.clearInterval(holdInterval.current);
    holdTimeout.current = null;
    holdInterval.current = null;
  }

  function startHold(direction: -1 | 1) {
    stopHold();
    setKeypadValue("");
    nudge(direction, 0.1);
    holdTimeout.current = window.setTimeout(() => {
      nudge(direction, 0.5);
      holdInterval.current = window.setInterval(() => nudge(direction, 0.5), HOLD_REPEAT);
    }, HOLD_DELAY);
  }

  function pressKey(key: string) {
    if (key === "backspace") {
      setKeypadValue((value) => value.slice(0, -1));
      return;
    }

    setKeypadValue((current) => {
      if (key === "." && current.includes(".")) return current;
      if (current.includes(".") && current.split(".")[1].length >= 1) return current;
      const next = current === "0" && key !== "." ? key : current + key;
      const numeric = Number(next);
      if (Number.isFinite(numeric) && numeric > MAX_SPEED) return current;
      return next.slice(0, 4);
    });
  }

  function applyKeypad() {
    const parsed = Number(keypadValue);
    if (!Number.isFinite(parsed) || keypadValue === "") return;
    selectSpeed(parsed);
    setKeypadValue("");
  }

  function recallPrevious() {
    const recalledSpeed = previousSpeed;
    setPreviousSpeed(speedRef.current);
    speedRef.current = recalledSpeed;
    setSpeed(recalledSpeed);
    setKeypadValue("");
  }

  function choosePreset(preset: number) {
    selectSpeed(preset);
    setKeypadValue("");
  }

  return (
    <main className="app-shell">
      <section className="controller" aria-label="Speed and pace controller">
        <section className="speed-panel" aria-live="polite">
          <div className="metrics">
            <div className="metric speed-metric">
              <div className="metric-value">
                <span>{speed.toFixed(1)}</span>
                <small>km/h</small>
              </div>
              <p>Speed</p>
            </div>
            <div className="metric pace-metric">
              <div className="metric-value">
                <span>{formatPace(speed)}</span>
                <small>/km</small>
              </div>
              <p>Pace</p>
            </div>
          </div>

          <div className="adjuster">
            <button
              className="adjust-button"
              aria-label="Decrease speed"
              onPointerDown={() => startHold(-1)}
              onPointerUp={stopHold}
              onPointerCancel={stopHold}
              onPointerLeave={stopHold}
              onContextMenu={(event) => event.preventDefault()}
              disabled={speed <= MIN_SPEED}
            >
              <span aria-hidden="true">−</span>
            </button>
            <div className="adjust-copy">
              <strong>± 0.1</strong>
              <span>Hold for 0.5</span>
            </div>
            <button
              className="adjust-button is-plus"
              aria-label="Increase speed"
              onPointerDown={() => startHold(1)}
              onPointerUp={stopHold}
              onPointerCancel={stopHold}
              onPointerLeave={stopHold}
              onContextMenu={(event) => event.preventDefault()}
              disabled={speed >= MAX_SPEED}
            >
              <span aria-hidden="true">+</span>
            </button>
          </div>
        </section>

        <section className="presets-section" aria-labelledby="presets-title">
          <h2 id="presets-title">Presets</h2>
          <div className="presets-grid">
            {PRESETS.map((preset) => (
              <button
                key={preset}
                className={speed === preset ? "is-selected" : ""}
                onClick={() => choosePreset(preset)}
                aria-label={`Set speed to ${preset} kilometres per hour`}
              >
                {preset}
              </button>
            ))}
          </div>
        </section>

        <div className="recall-row">
          <button
            className="previous-button"
            onClick={recallPrevious}
            disabled={previousSpeed === speed}
          >
            <span>Previous pace</span>
            <strong>{previousSpeed.toFixed(1)}</strong>
            <small>km/h</small>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M9 7 4 12l5 5" />
              <path d="M4 12h10a6 6 0 0 1 6 6" />
            </svg>
          </button>
          <div className={`keypad-display ${keypadValue ? "has-value" : ""}`} aria-live="polite">
            <span>{keypadValue || speed.toFixed(1)}</span>
            <small>km/h</small>
          </div>
        </div>

        <section className="keypad-section" aria-label="Enter an exact speed">
          <div className="keypad-grid">
            {["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0"].map((key) => (
              <button key={key} onClick={() => pressKey(key)}>
                {key}
              </button>
            ))}
            <button
              className="delete-key"
              onClick={() => pressKey("backspace")}
              aria-label="Delete last digit"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M20 5H9.8a2 2 0 0 0-1.6.8L3.5 12l4.7 6.2a2 2 0 0 0 1.6.8H20V5Z" />
                <path d="m11 9 6 6m0-6-6 6" />
              </svg>
            </button>
          </div>

          <button className="enter-button" onClick={applyKeypad} disabled={keypadValue === ""}>
            <span>Enter pace</span>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m13 6-6 6 6 6" />
              <path d="M7 12h11V7" />
            </svg>
          </button>
        </section>
      </section>
    </main>
  );
}
