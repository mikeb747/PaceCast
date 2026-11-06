import React, { useState } from 'react';
import { X, Download, Copy, Check, Smartphone } from 'lucide-react';
import { ANDROID_PROJECT_FILES, generateAndroidZip } from '../utils/android-project-export';

interface AndroidCodeStudioProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AndroidCodeStudio: React.FC<AndroidCodeStudioProps> = ({ isOpen, onClose }) => {
  const [selectedFileIndex, setSelectedFileIndex] = useState(0);
  const [copied, setCopied] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);

  if (!isOpen) return null;

  const currentFile = ANDROID_PROJECT_FILES[selectedFileIndex];

  const handleCopy = () => {
    navigator.clipboard.writeText(currentFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownloadZip = async () => {
    try {
      setIsDownloading(true);
      const blob = await generateAndroidZip();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'PaceCast-Android-Project.zip';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Error generating ZIP:', err);
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/85 backdrop-blur-sm animate-fade-in">
      <div className="w-full max-w-4xl bg-[#181a1b] border border-[#2e3235] rounded-2xl shadow-2xl overflow-hidden flex flex-col h-[88vh]">
        {/* Header */}
        <div className="px-6 py-4 bg-[#222527] border-b border-[#2e3235] flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="p-2 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-bold text-white flex items-center gap-2">
                PaceCast Android Native Source
                <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 font-mono">
                  Kotlin + Compose
                </span>
              </h2>
              <p className="text-xs text-neutral-400">
                Native BLE Peripheral GATT Service (0x1814 RSC)
              </p>
            </div>
          </div>
          <div className="flex items-center space-x-2">
            <button
              onClick={handleDownloadZip}
              disabled={isDownloading}
              className="px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-black text-xs font-bold transition flex items-center space-x-1.5 cursor-pointer shadow-sm"
              title="Download Android Studio Project (.ZIP)"
            >
              <Download className="w-3.5 h-3.5" />
              <span>{isDownloading ? 'Zipping...' : 'Download ZIP'}</span>
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-neutral-400 hover:text-white hover:bg-neutral-800 transition cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Workspace Layout */}
        <div className="flex-1 flex overflow-hidden">
          {/* File Explorer Tree */}
          <div className="w-64 border-r border-[#2e3235] bg-[#141517] flex flex-col">
            <div className="p-3 border-b border-[#2e3235] text-[11px] font-bold text-neutral-400 uppercase tracking-wider">
              Project Files
            </div>
            <div className="flex-1 overflow-y-auto p-2 space-y-1">
              {ANDROID_PROJECT_FILES.map((file, idx) => (
                <button
                  key={file.path}
                  onClick={() => setSelectedFileIndex(idx)}
                  className={`w-full text-left px-3 py-2 rounded-lg text-xs font-mono transition flex items-center justify-between ${
                    selectedFileIndex === idx
                      ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 font-semibold'
                      : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800/40'
                  }`}
                >
                  <span className="truncate">{file.name}</span>
                  <span className="text-[10px] uppercase text-neutral-400 ml-1">
                    {file.language}
                  </span>
                </button>
              ))}
            </div>
          </div>

          {/* Code Viewer */}
          <div className="flex-1 flex flex-col bg-[#111214] overflow-hidden">
            <div className="px-4 py-2 bg-[#1a1c1e] border-b border-[#2e3235] flex items-center justify-between">
              <span className="text-xs font-mono text-neutral-400 truncate">
                {currentFile.path}
              </span>
              <button
                onClick={handleCopy}
                className="px-2.5 py-1 rounded bg-[#25282a] hover:bg-[#2f3235] text-neutral-300 text-xs font-mono transition flex items-center space-x-1.5"
              >
                {copied ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                    <span className="text-emerald-400">Copied</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5" />
                    <span>Copy</span>
                  </>
                )}
              </button>
            </div>
            <div className="flex-1 overflow-auto p-4 font-mono text-xs text-neutral-300 leading-relaxed whitespace-pre selection:bg-emerald-500/30">
              <code>{currentFile.content}</code>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
