import React, { useState, useCallback } from 'react';
import { PaceController } from './components/PaceController';
import { BleTelemetryModal } from './components/BleTelemetryModal';
import { AndroidCodeStudio } from './components/AndroidCodeStudio';
import { RscPacket, encodeRscPacket, DEFAULT_SPEED_KMH } from './utils/pacecast-logic';

export default function App() {
  const [isBroadcasting, setIsBroadcasting] = useState<boolean>(true);
  const [isTelemetryOpen, setIsTelemetryOpen] = useState<boolean>(false);
  const [isAndroidCodeOpen, setIsAndroidCodeOpen] = useState<boolean>(false);

  const [currentPacket, setCurrentPacket] = useState<RscPacket>(() =>
    encodeRscPacket(DEFAULT_SPEED_KMH)
  );

  const [packetHistory, setPacketHistory] = useState<
    Array<{ timestamp: string; packet: RscPacket; id: number }>
  >([]);

  const handlePacketEmitted = useCallback((packet: RscPacket) => {
    setCurrentPacket(packet);
    const now = new Date();
    const timeStr =
      now.toTimeString().split(' ')[0] +
      '.' +
      String(now.getMilliseconds()).padStart(3, '0').slice(0, 2);

    setPacketHistory((prev) => [
      ...prev.slice(-25),
      {
        timestamp: timeStr,
        packet,
        id: Date.now() + Math.random(),
      },
    ]);
  }, []);

  return (
    <div className="h-screen h-[100dvh] max-h-screen max-h-[100dvh] w-full bg-black text-gray-100 flex flex-col overflow-hidden px-1 py-1 sm:px-2 sm:py-2 selection:bg-emerald-500 selection:text-black">
      <main className="w-full flex-1 flex flex-col items-center justify-center overflow-hidden min-h-0">
        <PaceController
          onOpenTelemetry={() => setIsTelemetryOpen(true)}
          onOpenAndroidCode={() => setIsAndroidCodeOpen(true)}
          isBroadcasting={isBroadcasting}
          onToggleBroadcasting={() => setIsBroadcasting(!isBroadcasting)}
          onPacketEmitted={handlePacketEmitted}
        />
      </main>

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
