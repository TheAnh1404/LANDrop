import React from 'react';
import { cn, formatBytes, formatSpeed, formatETA } from '@/lib/utils';
import { FileTransfer, TransferStatus } from '@/lib/types';
import { CheckCircle2, AlertCircle, Clock, Zap, ArrowRight, ShieldCheck, XCircle } from 'lucide-react';
import { Badge } from './badge';
import { Button } from './button';

export function Progress({
  value,
  max = 100,
  className,
  status = 'transferring',
}: {
  value: number;
  max?: number;
  className?: string;
  status?: TransferStatus;
}) {
  const percentage = Math.min(100, Math.max(0, (value / max) * 100));

  const barColors: Record<string, string> = {
    waiting: 'bg-slate-400',
    connecting: 'bg-amber-500 animate-pulse',
    transferring: 'bg-indigo-600',
    verifying: 'bg-teal-500',
    completed: 'bg-emerald-600',
    failed: 'bg-red-500',
    cancelled: 'bg-slate-400',
    declined: 'bg-slate-400',
    integrity_failed: 'bg-red-600',
  };

  return (
    <div className={cn('w-full bg-slate-100 rounded-full h-2 overflow-hidden', className)}>
      <div
        className={cn('h-full transition-all duration-300 ease-out rounded-full', barColors[status] || 'bg-indigo-600')}
        style={{ width: `${percentage}%` }}
      />
    </div>
  );
}

export function TransferProgressCard({
  transfer,
  onCancel,
  onRetry,
  compact = false,
}: {
  transfer: FileTransfer;
  onCancel?: (id: string) => void;
  onRetry?: (id: string) => void;
  compact?: boolean;
}) {
  const statusMeta: Record<
    TransferStatus,
    { label: string; icon: React.ReactNode; color: string; badge: 'default' | 'success' | 'warning' | 'danger' | 'neutral' | 'p2p' }
  > = {
    waiting: { label: 'Waiting for peer', icon: <Clock className="w-3.5 h-3.5" />, color: 'text-slate-500', badge: 'neutral' },
    connecting: { label: 'Negotiating WebRTC', icon: <Zap className="w-3.5 h-3.5 animate-pulse" />, color: 'text-amber-500', badge: 'warning' },
    transferring: { label: 'Transferring P2P', icon: <Zap className="w-3.5 h-3.5" />, color: 'text-indigo-600', badge: 'p2p' },
    verifying: { label: 'Verifying SHA-256', icon: <ShieldCheck className="w-3.5 h-3.5 animate-pulse" />, color: 'text-teal-600', badge: 'p2p' },
    completed: { label: 'Completed', icon: <CheckCircle2 className="w-3.5 h-3.5" />, color: 'text-emerald-600', badge: 'success' },
    failed: { label: 'Failed', icon: <AlertCircle className="w-3.5 h-3.5" />, color: 'text-red-600', badge: 'danger' },
    cancelled: { label: 'Cancelled', icon: <XCircle className="w-3.5 h-3.5" />, color: 'text-slate-500', badge: 'neutral' },
    declined: { label: 'Declined', icon: <XCircle className="w-3.5 h-3.5" />, color: 'text-slate-500', badge: 'neutral' },
    integrity_failed: { label: 'Integrity Check Failed', icon: <AlertCircle className="w-3.5 h-3.5" />, color: 'text-red-600', badge: 'danger' },
  };

  const meta = statusMeta[transfer.status] || statusMeta.waiting;

  if (compact) {
    return (
      <div className="bg-white rounded-lg border border-slate-200 p-3 shadow-sm hover:border-slate-300 transition-colors">
        <div className="flex items-center justify-between text-xs mb-1.5">
          <span className="font-semibold text-slate-800 truncate max-w-[200px]" title={transfer.fileName}>
            {transfer.fileName}
          </span>
          <span className="text-slate-500 font-medium">
            {transfer.progressPercent}%
          </span>
        </div>
        <Progress value={transfer.progressPercent} status={transfer.status} className="h-1.5 mb-2" />
        <div className="flex items-center justify-between text-[11px] text-slate-500">
          <span className="flex items-center gap-1">
            <span className={meta.color}>{meta.icon}</span>
            <span>{meta.label}</span>
          </span>
          {transfer.status === 'transferring' && (
            <span>{formatSpeed(transfer.speedBytesPerSec)}</span>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white rounded-xl border border-slate-200/90 p-5 shadow-card">
      {/* File Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-slate-900 truncate max-w-md" title={transfer.fileName}>
              {transfer.fileName}
            </h3>
            <Badge variant={meta.badge} size="sm">
              {meta.icon}
              {meta.label}
            </Badge>
          </div>
          <div className="flex items-center gap-2 mt-1 text-xs text-slate-500">
            <span className="font-medium text-slate-700">{transfer.sender.userName}</span>
            <ArrowRight className="w-3 h-3 text-slate-400" />
            <span className="font-medium text-slate-700">{transfer.receiver.userName}</span>
            <span className="text-slate-300">•</span>
            <span className="text-teal-700 font-medium bg-teal-50 px-1.5 py-0.5 rounded text-[11px] border border-teal-200/60">
              {transfer.mode}
            </span>
          </div>
        </div>
        <div className="text-right">
          <div className="text-sm font-semibold text-slate-900">
            {formatBytes(transfer.transferredBytes)} / {formatBytes(transfer.fileSize)}
          </div>
          <div className="text-xs text-slate-500 mt-0.5 font-medium">
            {transfer.progressPercent}%
          </div>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="mt-4">
        <Progress value={transfer.progressPercent} status={transfer.status} className="h-2" />
      </div>

      {/* Metrics & Actions */}
      <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
        <div className="flex items-center gap-4">
          {transfer.status === 'transferring' ? (
            <>
              <span className="font-medium text-slate-700">
                ⚡ {formatSpeed(transfer.speedBytesPerSec)}
              </span>
              <span>ETA: {formatETA(transfer.etaSeconds)}</span>
            </>
          ) : transfer.status === 'completed' ? (
            <span className="text-emerald-700 font-medium">Verified SHA-256 match</span>
          ) : transfer.errorMessage ? (
            <span className="text-red-600 font-medium">{transfer.errorMessage}</span>
          ) : (
            <span>Ready for data channel stream</span>
          )}
        </div>

        <div className="flex items-center gap-2">
          {transfer.status === 'transferring' && onCancel && (
            <Button variant="ghost" size="xs" onClick={() => onCancel(transfer.id)}>
              Cancel
            </Button>
          )}
          {(transfer.status === 'failed' || transfer.status === 'cancelled') && onRetry && (
            <Button variant="outline" size="xs" onClick={() => onRetry(transfer.id)}>
              Retry
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
