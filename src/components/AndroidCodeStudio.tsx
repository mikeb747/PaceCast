import React, { useState } from 'react';
import { X, Download, Copy, Check, FileCode, Smartphone, Terminal, ExternalLink } from 'lucide-react';
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
                Native BLE Peripheral (RSC 0x1814) for Garmin Forerunner 255
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleDownloadZip}
              disabled={isDownloading}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold rounded-lg bg-emerald-500 hover:bg-emerald-400 text-black transition shadow-sm"
              title="Download Android Studio project ZIP"
            >
              <Download className="w-4 h-4" />
              <span>{isDownloading ? 'Packaging...' : 'Download Project ZIP'}</span>
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-neutral-400 hover:text-white hover:bg-neutral-800 transition"
              aria-label="Close dialog"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Content Area */}
        <div className="flex-1 flex flex-col md:flex-row overflow-hidden">
          {/* File Explorer Sidebar */}
          <div className="w-full md:w-64 bg-[#141517] border-r border-[#2e3235] p-3 overflow-y-auto space-y-1">
            <div className="text-[11px] font-bold text-neutral-400 uppercase tracking-wider px-2 py-1.5">
              Project Files
            </div>
            {ANDROID_PROJECT_FILES.map((file, idx) => (
              <button
                key={file.path}
                onClick={() => setSelectedFileIndex(idx)}
                className={`w-full text-left px-3 py-2 rounded-lg text-xs font-mono flex items-center justify-between transition ${
                  selectedFileIndex === idx
                    ? 'bg-emerald-500/15 text-emerald-300 font-bold border border-emerald-500/30'
                    : 'text-neutral-300 hover:bg-[#1e2022] hover:text-white border border-transparent'
                }`}
              >
                <div className="flex items-center gap-2 truncate">
                  <FileCode className="w-3.5 h-3.5 shrink-0 text-emerald-400" />
                  <span className="truncate">{file.name}</span>
                </div>
              </button>
            ))}

            {/* Quick Garmin Instructions Box */}
            <div className="mt-6 p-3 rounded-xl bg-[#1e2022] border border-[#2a2d30] text-xs space-y-1.5">
              <div className="font-bold text-white flex items-center gap-1.5">
                <Terminal className="w-3.5 h-3.5 text-emerald-400" />
                Quick Run
              </div>
              <p className="text-[11px] text-neutral-400 leading-relaxed">
                Open in Android Studio (Hedgehog+), build APK, run on your Android phone, then pair your Garmin Forerunner 255 as a <strong>Foot Pod (FP)</strong>.
              </p>
            </div>
          </div>

          {/* Code Viewer */}
          <div className="flex-1 flex flex-col bg-[#0e1011] overflow-hidden">
            {/* Code Header */}
            <div className="px-4 py-2.5 bg-[#181a1b] border-b border-[#2e3235] flex items-center justify-between">
              <span className="text-xs font-mono text-neutral-300">{currentFile.path}</span>
              <button
                onClick={handleCopy}
                className="flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded bg-[#272a2d] hover:bg-[#34383c] text-white transition border border-[#374151]"
              >
                {copied ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                    <span className="text-emerald-400">Copied!</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5" />
                    <span>Copy Code</span>
                  </>
                )}
              </button>
            </div>

            {/* Code Pre Container */}
            <div className="flex-1 p-4 overflow-auto font-mono text-xs text-neutral-200 leading-relaxed">
              <pre className="whitespace-pre">{currentFile.content}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
