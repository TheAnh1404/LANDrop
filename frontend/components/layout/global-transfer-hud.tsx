'use client';

import React, { useState } from 'react';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { formatBytes, formatSpeed, formatETA } from '@/lib/utils';
import { Progress } from '../ui/transfer-progress';
import { Button } from '../ui/button';
import { Zap, X, ChevronUp, ChevronDown, CheckCircle2, AlertCircle } from 'lucide-react';
import Link from 'next/link';

export function GlobalTransferHud() {
  const { activeTransfer, cancelTransfer } = useTransfer();
  const [minimized, setMinimized] = useState(false);

  if (
    !activeTransfer ||
    activeTransfer.status === 'completed' ||
    activeTransfer.status === 'cancelled' ||
    activeTransfer.status === 'failed' ||
    activeTransfer.status === 'declined'
  ) {
    return null;
  }

  return (
    <div className="fixed bottom-5 right-5 z-40 w-96 max-w-[calc(100vw-2.5rem)] animate-in slide-in-from-bottom-5 duration-200">
      <div className="bg-slate-900 text-white rounded-xl shadow-panel border border-slate-800 p-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2 min-w-0">
            <span className="p-1 rounded bg-teal-500/20 text-teal-400">
              <Zap className="w-3.5 h-3.5" />
            </span>
            <div className="min-w-0">
              <div className="text-xs font-semibold text-white truncate" title={activeTransfer.fileName}>
                {activeTransfer.fileName}
              </div>
              <div className="text-[10px] text-slate-400">
                Direct P2P • {activeTransfer.sender.userName} → {activeTransfer.receiver.userName}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => setMinimized(!minimized)}
              className="p-1 text-slate-400 hover:text-white rounded hover:bg-slate-800 transition-colors"
              aria-label={minimized ? 'Expand' : 'Minimize'}
            >
              {minimized ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
            </button>
            <button
              type="button"
              onClick={() => cancelTransfer(activeTransfer.id)}
              className="p-1 text-slate-400 hover:text-red-400 rounded hover:bg-slate-800 transition-colors"
              aria-label="Cancel transfer"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* Expanded Progress Body */}
        {!minimized && (
          <div className="mt-3 space-y-2">
            <Progress value={activeTransfer.progressPercent} status={activeTransfer.status} className="h-1.5 bg-slate-800" />
            <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono">
              <span>
                {formatBytes(activeTransfer.transferredBytes)} / {formatBytes(activeTransfer.fileSize)} ({activeTransfer.progressPercent}%)
              </span>
              <span>
                {activeTransfer.status === 'transferring'
                  ? `${formatSpeed(activeTransfer.speedBytesPerSec)} • ETA ${formatETA(activeTransfer.etaSeconds)}`
                  : activeTransfer.status === 'verifying'
                  ? 'Verifying SHA-256...'
                  : 'Connecting...'}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
