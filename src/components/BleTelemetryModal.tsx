import React from 'react';
import { X, Radio, Zap } from 'lucide-react';
import { RscPacket } from '../utils/pacecast-logic';

interface BleTelemetryModalProps {
  isOpen: boolean;
  onClose: () => void;
  isBroadcasting: boolean;
  onToggleBroadcasting: () => void;
  currentPacket: RscPacket;
  packetHistory: Array<{ timestamp: string; packet: RscPacket; id: number }>;
}

export const BleTelemetryModal: React.FC<BleTelemetryModalProps> = ({
  isOpen,
  onClose,
  isBroadcasting,
  onToggleBroadcasting,
  currentPacket,
  packetHistory,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-2xl bg-[#181a1b] border border-[#2e3235] rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        {/* Header */}
        <div className="px-6 py-4 bg-[#222527] border-b border-[#2e3235] flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="p-2 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              <Radio className="w-5 h-5 animate-pulse" />
            </div>
            <div>
              <h2 className="text-lg font-bold text-white flex items-center gap-2">
                BLE Peripheral Monitor
                <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 font-mono">
                  0x1814 RSC
                </span>
              </h2>
              <p className="text-xs text-neutral-400">
                Garmin Forerunner 255 Foot Pod GATT Telemetry
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-neutral-400 hover:text-white hover:bg-neutral-800 transition"
            aria-label="Close dialog"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content Body */}
        <div className="p-6 space-y-6 overflow-y-auto">
          {/* Status & Control Banner */}
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between p-4 rounded-xl bg-[#222527] border border-[#2e3235] gap-4">
            <div>
              <div className="flex items-center gap-2">
                <span
                  className={`w-3 h-3 rounded-full ${
                    isBroadcasting ? 'bg-emerald-400 animate-pulse-dot' : 'bg-neutral-500'
                  }`}
                />
                <span className="text-sm font-semibold text-white">
                  Server Status: {isBroadcasting ? 'Advertising Active (1 Hz)' : 'GATT Server Idle'}
                </span>
              </div>
              <p className="text-xs text-neutral-400 mt-1">
                Advertising Name: <span className="font-mono text-emerald-400">"PaceCast"</span> | Service:{' '}
                <span className="font-mono text-neutral-300">0x1814 (RSC)</span>
              </p>
            </div>
            <button
              onClick={onToggleBroadcasting}
              className={`px-4 py-2 text-xs font-bold rounded-lg border transition ${
                isBroadcasting
                  ? 'bg-red-500/10 text-red-400 border-red-500/30 hover:bg-red-500/20'
                  : 'bg-emerald-500 text-black border-emerald-400 hover:bg-emerald-400'
              }`}
            >
              {isBroadcasting ? 'Stop Advertising' : 'Start Advertising'}
            </button>
          </div>

          {/* Current Packet Live Decomposition */}
          <div className="space-y-3">
            <h3 className="text-xs font-bold text-neutral-400 uppercase tracking-wider flex items-center gap-1.5">
              <Zap className="w-3.5 h-3.5 text-emerald-400" />
              Live 4-Byte Little-Endian Payload (0x2A53)
            </h3>
            <div className="p-4 rounded-xl bg-[#141517] border border-[#2e3235] font-mono">
              <div className="flex items-baseline justify-between mb-3 border-b border-[#25282a] pb-2">
                <span className="text-xs text-neutral-400">Raw Hex Output:</span>
                <span className="text-xl font-bold tracking-widest text-emerald-400">
                  {currentPacket.hexPayload}
                </span>
              </div>

              <div className="grid grid-cols-4 gap-2 text-center text-xs">
                <div className="p-2 rounded bg-[#1c1e20] border border-[#282b2e]">
                  <p className="text-[10px] text-neutral-400">Byte 0 (Flags)</p>
                  <p className="font-bold text-white text-sm mt-0.5">0x00</p>
                  <p className="text-[10px] text-neutral-500">Speed only</p>
                </div>
                <div className="p-2 rounded bg-[#1c1e20] border border-[#282b2e]">
                  <p className="text-[10px] text-neutral-400">Byte 1 (LSB)</p>
                  <p className="font-bold text-emerald-400 text-sm mt-0.5">
                    0x{currentPacket.bytes[1].toString(16).padStart(2, '0').toUpperCase()}
                  </p>
                  <p className="text-[10px] text-neutral-500">Speed low</p>
                </div>
                <div className="p-2 rounded bg-[#1c1e20] border border-[#282b2e]">
                  <p className="text-[10px] text-neutral-400">Byte 2 (MSB)</p>
                  <p className="font-bold text-emerald-400 text-sm mt-0.5">
                    0x{currentPacket.bytes[2].toString(16).padStart(2, '0').toUpperCase()}
                  </p>
                  <p className="text-[10px] text-neutral-500">Speed high</p>
                </div>
                <div className="p-2 rounded bg-[#1c1e20] border border-[#282b2e]">
                  <p className="text-[10px] text-neutral-400">Byte 3 (Cadence)</p>
                  <p className="font-bold text-white text-sm mt-0.5">0x00</p>
                  <p className="text-[10px] text-neutral-500">0 RPM</p>
                </div>
              </div>
            </div>
          </div>

          {/* Packet Stream History */}
          <div className="space-y-3">
            <h3 className="text-xs font-bold text-neutral-400 uppercase tracking-wider">
              Recent 1 Hz Broadcast Log (Garmin 255 Stream)
            </h3>
            <div className="rounded-xl border border-[#2e3235] bg-[#141517] overflow-hidden">
              <div className="max-h-48 overflow-y-auto divide-y divide-[#25282a] font-mono text-xs">
                {packetHistory.length === 0 ? (
                  <div className="p-4 text-center text-neutral-500">No packets broadcast yet</div>
                ) : (
                  packetHistory
                    .slice()
                    .reverse()
                    .map((item) => (
                      <div
                        key={item.id}
                        className="p-2.5 flex items-center justify-between hover:bg-white/[0.02]"
                      >
                        <span className="text-neutral-400">{item.timestamp}</span>
                        <span className="font-bold text-emerald-400">{item.packet.hexPayload}</span>
                        <span className="text-neutral-300">
                          {item.packet.speedKmh.toFixed(1)} km/h ({item.packet.speedMs.toFixed(2)}{' '}
                          m/s)
                        </span>
                      </div>
                    ))
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
