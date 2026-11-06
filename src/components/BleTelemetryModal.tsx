import React, { useState } from 'react';
import { X, Radio, CheckCircle, Info, Activity, Clock, ShieldCheck, Zap } from 'lucide-react';
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

              {/* 4 Bytes breakdown table */}
              <div className="grid grid-cols-4 gap-2 text-center text-xs">
                <div className="p-2 rounded bg-[#222527] border border-[#2e3235]">
                  <div className="text-[10px] text-neutral-400">Byte 0 (Flags)</div>
                  <div className="text-sm font-bold text-white mt-1">
                    0x{currentPacket.bytes[0].toString(16).padStart(2, '0').toUpperCase()}
                  </div>
                  <div className="text-[9px] text-neutral-400 mt-0.5">Speed/Cadence only</div>
                </div>
                <div className="p-2 rounded bg-[#222527] border border-emerald-500/40 bg-emerald-500/5">
                  <div className="text-[10px] text-emerald-400">Byte 1 (Speed LSB)</div>
                  <div className="text-sm font-bold text-emerald-300 mt-1">
                    0x{currentPacket.bytes[1].toString(16).padStart(2, '0').toUpperCase()}
                  </div>
                  <div className="text-[9px] text-neutral-400 mt-0.5">
                    {currentPacket.bytes[1]}
                  </div>
                </div>
                <div className="p-2 rounded bg-[#222527] border border-emerald-500/40 bg-emerald-500/5">
                  <div className="text-[10px] text-emerald-400">Byte 2 (Speed MSB)</div>
                  <div className="text-sm font-bold text-emerald-300 mt-1">
                    0x{currentPacket.bytes[2].toString(16).padStart(2, '0').toUpperCase()}
                  </div>
                  <div className="text-[9px] text-neutral-400 mt-0.5">
                    {currentPacket.bytes[2]}
                  </div>
                </div>
                <div className="p-2 rounded bg-[#222527] border border-[#2e3235]">
                  <div className="text-[10px] text-neutral-400">Byte 3 (Cadence)</div>
                  <div className="text-sm font-bold text-white mt-1">
                    0x{currentPacket.bytes[3].toString(16).padStart(2, '0').toUpperCase()}
                  </div>
                  <div className="text-[9px] text-neutral-400 mt-0.5">0 RPM</div>
                </div>
              </div>

              {/* Conversion Math Calculation Box */}
              <div className="mt-3 text-xs text-neutral-400 pt-2 border-t border-[#25282a] flex flex-col gap-1">
                <div className="flex justify-between">
                  <span>Target Treadmill Speed:</span>
                  <span className="text-white font-bold">{currentPacket.speedKmh.toFixed(1)} km/h</span>
                </div>
                <div className="flex justify-between">
                  <span>Speed in meters/second:</span>
                  <span className="text-neutral-300">
                    {currentPacket.speedKmh} / 3.6 = <strong className="text-white">{currentPacket.speedMs} m/s</strong>
                  </span>
                </div>
                <div className="flex justify-between">
                  <span>Unsigned 16-bit Raw Speed (1/256 m/s):</span>
                  <span className="text-emerald-400 font-bold">
                    round({currentPacket.speedMs} × 256) = {currentPacket.rawSpeed} (0x{currentPacket.rawSpeed.toString(16).padStart(4, '0').toUpperCase()})
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Garmin Forerunner 255 GATT Specifications */}
          <div className="space-y-3">
            <h3 className="text-xs font-bold text-neutral-400 uppercase tracking-wider flex items-center gap-1.5">
              <ShieldCheck className="w-3.5 h-3.5 text-blue-400" />
              GATT Specifications for Garmin Forerunner 255
            </h3>
            <div className="p-4 rounded-xl bg-[#222527] border border-[#2e3235] space-y-2 text-xs">
              <div className="flex items-start gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 mt-1.5 shrink-0" />
                <div>
                  <span className="text-white font-semibold">Service 0x1814 (Running Speed & Cadence):</span>
                  <p className="text-neutral-400">Standard Bluetooth SIG primary service for foot pod sensors.</p>
                </div>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 mt-1.5 shrink-0" />
                <div>
                  <span className="text-white font-semibold">Characteristic 0x2A54 (RSC Feature - Read):</span>
                  <p className="text-neutral-400">
                    Returns <code className="text-emerald-400 bg-black/40 px-1 py-0.5 rounded">[0x00, 0x00]</code> specifying instantaneous speed and cadence supported.
                  </p>
                </div>
              </div>
              <div className="flex items-start gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 mt-1.5 shrink-0" />
                <div>
                  <span className="text-white font-semibold">Characteristic 0x2A53 & CCCD 0x2902 (Notify):</span>
                  <p className="text-neutral-400">
                    When Garmin writes 0x0001 to CCCD 0x2902, the server ACKs with <code className="text-emerald-400 bg-black/40 px-1 py-0.5 rounded">GATT_SUCCESS</code> and pushes 1Hz notifications.
                  </p>
                </div>
              </div>
            </div>
          </div>

          {/* Live Packet History Log */}
          <div className="space-y-2">
            <h3 className="text-xs font-bold text-neutral-400 uppercase tracking-wider flex items-center gap-1.5">
              <Clock className="w-3.5 h-3.5 text-amber-400" />
              1 Hz Broadcast Transmission History
            </h3>
            <div className="max-h-36 overflow-y-auto space-y-1.5 p-2 rounded-xl bg-[#141517] border border-[#2e3235] font-mono text-xs">
              {packetHistory.length === 0 ? (
                <div className="text-center py-4 text-neutral-500">
                  Waiting for broadcast ticker...
                </div>
              ) : (
                packetHistory.slice(-8).reverse().map((entry) => (
                  <div
                    key={entry.id}
                    className="flex items-center justify-between px-2.5 py-1 rounded bg-[#1e2022] border border-[#2a2d30]"
                  >
                    <span className="text-neutral-400">{entry.timestamp}</span>
                    <span className="text-neutral-300">{entry.packet.speedKmh.toFixed(1)} km/h</span>
                    <span className="text-emerald-400 font-bold">{entry.packet.hexPayload}</span>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="px-6 py-3 bg-[#222527] border-t border-[#2e3235] flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-1.5 text-xs font-semibold text-white bg-neutral-700 hover:bg-neutral-600 rounded-lg transition"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
