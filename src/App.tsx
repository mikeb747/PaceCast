import React, { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { PaceController } from './components/PaceController';

export default function App() {
  const [isBroadcasting, setIsBroadcasting] = useState<boolean>(true);
  const [showStopBroadcastingConfirm, setShowStopBroadcastingConfirm] = useState<boolean>(false);

  const handleToggleBroadcasting = () => {
    if (isBroadcasting) {
      setShowStopBroadcastingConfirm(true);
    } else {
      setIsBroadcasting(true);
    }
  };

  const handleConfirmStopBroadcasting = () => {
    setIsBroadcasting(false);
    setShowStopBroadcastingConfirm(false);
  };

  return (
    <div className="h-screen h-[100dvh] max-h-screen max-h-[100dvh] w-full bg-black text-gray-100 flex flex-col overflow-hidden px-1 py-1 sm:px-2 sm:py-2 selection:bg-emerald-500 selection:text-black">
      <main className="w-full flex-1 flex flex-col items-center justify-center overflow-hidden min-h-0">
        <PaceController
          isBroadcasting={isBroadcasting}
          onToggleBroadcasting={handleToggleBroadcasting}
        />
      </main>

      {/* Confirmation Dialog when turning off broadcasting */}
      {showStopBroadcastingConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm animate-fade-in">
          <div className="w-full max-w-sm bg-[#181a1b] border border-[#2e3235] rounded-2xl p-5 shadow-2xl space-y-4">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-red-500/15 border border-red-500/30 flex items-center justify-center text-red-400">
                <AlertTriangle className="w-5 h-5" />
              </div>
              <div>
                <h3 className="text-base font-bold text-white">Turn Off Broadcasting?</h3>
                <p className="text-xs text-neutral-400">BLE RSC Foot Pod Server</p>
              </div>
            </div>

            <p className="text-xs sm:text-sm text-neutral-300 leading-relaxed">
              Are you sure you want to stop broadcasting? Your Garmin watch or treadmill will lose connection to the foot pod.
            </p>

            <div className="flex items-center gap-2 pt-1">
              <button
                onClick={() => setShowStopBroadcastingConfirm(false)}
                className="flex-1 py-2.5 px-3 rounded-xl border border-[#2e3235] text-xs sm:text-sm font-bold text-neutral-300 hover:text-white hover:bg-neutral-800 transition cursor-pointer"
              >
                Keep Broadcasting
              </button>
              <button
                onClick={handleConfirmStopBroadcasting}
                className="flex-1 py-2.5 px-3 rounded-xl bg-red-500 hover:bg-red-600 text-white text-xs sm:text-sm font-bold transition shadow-lg shadow-red-500/20 cursor-pointer"
              >
                Turn Off
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
