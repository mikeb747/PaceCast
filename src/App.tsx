import React, { useState, useCallback } from 'react';
import { PaceController } from './components/PaceController';
import { BleTelemetryModal } from './components/BleTelemetryModal';
import { AndroidCodeStudio } from './components/AndroidCodeStudio';
import { RscPacket, encodeRscPacket, DEFAULT_SPEED_KMH } from './utils/pacecast-logic';
import { Smartphone, Monitor, Radio, Code2, ShieldAlert } from 'lucide-react';

export default function App() {
  const [isBroadcasting, setIsBroadcasting] = useState<boolean>(true);
  const [isTelemetryOpen, setIsTelemetryOpen] = useState<boolean>(false);
  const [isAndroidCodeOpen, setIsAndroidCodeOpen] = useState<boolean>(false);
  const [isPhoneFrameMode, setIsPhoneFrameMode] = useState<boolean>(false);

  const [currentPacket, setCurrentPacket] = useState<RscPacket>(() =>
    encodeRscPacket(DEFAULT_SPEED_KMH)
  );

  const [packetHistory, setPacketHistory] = useState<
    Array<{ timestamp: string; packet: RscPacket; id: number }>
  >([]);

  const handlePacketEmitted = useCallback((packet: RscPacket) => {
    setCurrentPacket(packet);
    const now = new Date();
    const timeStr = now.toTimeString().split(' ')[0] + '.' + String(now.getMilliseconds()).padStart(3, '0').slice(0, 2);

    setPacketHistory((prev) => [
      ...prev.slice(-25), // keep last 25 items
      {
        timestamp: timeStr,
        packet,
        id: Date.now() + Math.random(),
      },
    ]);
  }, []);

  return (
    <div className="min-h-screen bg-[#3a3d40] text-gray-100 flex flex-col justify-between p-3 sm:p-6 antialiased selection:bg-emerald-500 selection:text-black">
      {/* Top Banner & Control Dock */}
      <header className="max-w-2xl mx-auto w-full mb-4 flex items-center justify-between text-xs text-neutral-300">
        <div className="flex items-center gap-2">
          <div className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
          <span className="font-semibold text-white tracking-wide">PaceCast BLE Engine</span>
          <span className="hidden sm:inline text-neutral-400">| Garmin Forerunner 255 Foot Pod</span>
        </div>

        {/* Viewport & Modal Toggles */}
        <div className="flex items-center gap-1.5 bg-[#181a1b] border border-[#2e3235] p-1 rounded-xl">
          <button
            onClick={() => setIsPhoneFrameMode(!isPhoneFrameMode)}
            className={`flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium transition cursor-pointer ${
              isPhoneFrameMode ? 'bg-[#272a2d] text-emerald-400 font-bold' : 'text-neutral-400 hover:text-white'
            }`}
            title="Toggle Smartphone Frame View"
          >
            {isPhoneFrameMode ? <Smartphone className="w-3.5 h-3.5" /> : <Monitor className="w-3.5 h-3.5" />}
            <span className="hidden sm:inline">{isPhoneFrameMode ? 'Phone Frame' : 'Standard'}</span>
          </button>

          <button
            onClick={() => setIsTelemetryOpen(true)}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium text-neutral-400 hover:text-emerald-400 hover:bg-[#222527] transition cursor-pointer"
            title="Inspect live 0x1814 RSC BLE packet stream"
          >
            <Radio className="w-3.5 h-3.5 text-emerald-400" />
            <span className="hidden sm:inline">Telemetry</span>
          </button>

          <button
            onClick={() => setIsAndroidCodeOpen(true)}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-medium text-neutral-400 hover:text-emerald-400 hover:bg-[#222527] transition cursor-pointer"
            title="View Kotlin & Jetpack Compose Android source files"
          >
            <Code2 className="w-3.5 h-3.5 text-emerald-400" />
            <span className="hidden sm:inline">Android Code</span>
          </button>
        </div>
      </header>

      {/* Main Controller Shell */}
      <main className="flex-1 flex items-center justify-center my-auto py-2 w-full">
        {isPhoneFrameMode ? (
          /* Realistic Smartphone Bezel Simulation */
          <div className="relative border-[10px] border-[#181a1b] rounded-[48px] shadow-[0_25px_60px_-15px_rgba(0,0,0,0.8)] bg-[#181a1b] max-w-[430px] w-full max-h-[92vh] flex flex-col overflow-hidden">
            {/* Speaker & camera notch */}
            <div className="w-full h-6 bg-[#181a1b] flex items-center justify-center relative z-20 shrink-0">
              <div className="w-20 h-3.5 bg-black rounded-full flex items-center justify-center">
                <div className="w-2 h-2 rounded-full bg-neutral-800" />
              </div>
            </div>
            {/* Screen Content */}
            <div className="p-2 sm:p-3 overflow-y-auto flex-1">
              <PaceController
                onOpenTelemetry={() => setIsTelemetryOpen(true)}
                onOpenAndroidCode={() => setIsAndroidCodeOpen(true)}
                isBroadcasting={isBroadcasting}
                onToggleBroadcasting={() => setIsBroadcasting(!isBroadcasting)}
                onPacketEmitted={handlePacketEmitted}
              />
            </div>
            {/* Bottom Home indicator */}
            <div className="w-full h-5 bg-[#181a1b] flex items-center justify-center shrink-0">
              <div className="w-24 h-1 bg-neutral-600 rounded-full" />
            </div>
          </div>
        ) : (
          <PaceController
            onOpenTelemetry={() => setIsTelemetryOpen(true)}
            onOpenAndroidCode={() => setIsAndroidCodeOpen(true)}
            isBroadcasting={isBroadcasting}
            onToggleBroadcasting={() => setIsBroadcasting(!isBroadcasting)}
            onPacketEmitted={handlePacketEmitted}
          />
        )}
      </main>

      {/* Bottom Footer Information */}
      <footer className="max-w-2xl mx-auto w-full mt-4 text-center text-xs text-neutral-400 flex flex-col sm:flex-row items-center justify-between gap-2 border-t border-neutral-700/40 pt-3">
        <div className="flex items-center gap-2">
          <span className="inline-block w-2 h-2 rounded-full bg-emerald-500" />
          <span>Garmin Foot Pod BLE Protocol (Service 0x1814 / Char 0x2A53)</span>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setIsAndroidCodeOpen(true)}
            className="text-emerald-400 hover:underline cursor-pointer"
          >
            Export Native Android APK Project
          </button>
          <span>•</span>
          <button
            onClick={() => setIsTelemetryOpen(true)}
            className="text-neutral-300 hover:text-white cursor-pointer"
          >
            GATT Packet Inspector
          </button>
        </div>
      </footer>

      {/* BLE Telemetry Inspector Modal */}
      <BleTelemetryModal
        isOpen={isTelemetryOpen}
        onClose={() => setIsTelemetryOpen(false)}
        isBroadcasting={isBroadcasting}
        onToggleBroadcasting={() => setIsBroadcasting(!isBroadcasting)}
        currentPacket={currentPacket}
        packetHistory={packetHistory}
      />

      {/* Android Kotlin & Compose Source Code Studio */}
      <AndroidCodeStudio
        isOpen={isAndroidCodeOpen}
        onClose={() => setIsAndroidCodeOpen(false)}
      />
    </div>
  );
}
