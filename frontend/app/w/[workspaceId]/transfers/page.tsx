'use client';

import React, { useState } from 'react';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { Tabs } from '@/components/ui/file-icon';
import { Progress } from '@/components/ui/transfer-progress';
import { FileIcon } from '@/components/ui/file-icon';
import { Modal } from '@/components/ui/modal';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { FileTransfer } from '@/lib/types';
import { formatBytes, formatSpeed, formatDate } from '@/lib/utils';
import {
  ArrowLeftRight,
  Send,
  Zap,
  CheckCircle2,
  AlertCircle,
  XCircle,
  RefreshCw,
  Info,
  Clock,
} from 'lucide-react';

export default function TransfersPage({
  params,
}: {
  params: { workspaceId: string };
}) {
  const { transfers, cancelTransfer, retryTransfer, openSendModal } = useTransfer();
  const [activeTab, setActiveTab] = useState<'all' | 'active' | 'completed' | 'failed'>('active');
  const [selectedTransfer, setSelectedTransfer] = useState<FileTransfer | null>(null);

  const activeTransfers = transfers.filter(
    (t) => t.status === 'transferring' || t.status === 'connecting' || t.status === 'waiting' || t.status === 'verifying'
  );
  const completedTransfers = transfers.filter((t) => t.status === 'completed');
  const failedTransfers = transfers.filter(
    (t) => t.status === 'failed' || t.status === 'cancelled' || t.status === 'declined' || t.status === 'integrity_failed'
  );

  const tabs = [
    { id: 'active', label: 'Active', count: activeTransfers.length },
    { id: 'completed', label: 'Completed', count: completedTransfers.length },
    { id: 'failed', label: 'Failed / Cancelled', count: failedTransfers.length },
    { id: 'all', label: 'All Transfers', count: transfers.length },
  ];

  const displayedTransfers =
    activeTab === 'active'
      ? activeTransfers
      : activeTab === 'completed'
      ? completedTransfers
      : activeTab === 'failed'
      ? failedTransfers
      : transfers;

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <WorkspaceTopBar
        title="P2P Transfers"
        subtitle="Real-time WebRTC DataChannel browser-to-browser streaming monitor"
        actions={
          <Button
            variant="primary"
            size="xs"
            onClick={() => openSendModal()}
            leftIcon={<Send className="w-3.5 h-3.5" />}
          >
            New Transfer
          </Button>
        }
      />

      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Tabs */}
        <div className="px-6 pt-3 bg-white border-b border-slate-200">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={(id) => setActiveTab(id as any)} />
        </div>

        {/* Table Content */}
        <div className="flex-1 overflow-y-auto">
          {displayedTransfers.length === 0 ? (
            <div className="p-12 text-center text-slate-400 text-xs">
              No {activeTab} transfers found.
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[28%]">File</TableHead>
                  <TableHead className="w-[14%]">From</TableHead>
                  <TableHead className="w-[14%]">To</TableHead>
                  <TableHead className="w-[10%]">Size</TableHead>
                  <TableHead className="w-[14%]">Progress / Speed</TableHead>
                  <TableHead className="w-[10%]">Status</TableHead>
                  <TableHead className="w-[10%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {displayedTransfers.map((t) => (
                  <TableRow
                    key={t.id}
                    onClick={() => setSelectedTransfer(t)}
                    className="cursor-pointer hover:bg-slate-50"
                  >
                    <TableCell>
                      <div className="flex items-center gap-2.5 min-w-0">
                        <FileIcon type={t.fileType} />
                        <div className="min-w-0">
                          <span className="font-semibold text-slate-900 text-xs truncate block max-w-xs" title={t.fileName}>
                            {t.fileName}
                          </span>
                          <span className="text-[10px] text-teal-700 font-mono">
                            {t.mode}
                          </span>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-slate-700">
                      <div>{t.sender.userName}</div>
                      <div className="text-[10px] text-slate-400 font-mono truncate max-w-[130px]">
                        {t.sender.sessionName}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-slate-700">
                      <div>{t.receiver.userName}</div>
                      <div className="text-[10px] text-slate-400 font-mono truncate max-w-[130px]">
                        {t.receiver.sessionName}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs font-mono text-slate-700">
                      {formatBytes(t.fileSize)}
                    </TableCell>
                    <TableCell>
                      <div className="space-y-1">
                        <div className="flex items-center justify-between text-[11px]">
                          <span className="font-medium text-slate-700">{t.progressPercent}%</span>
                          {t.status === 'transferring' && (
                            <span className="text-slate-500 font-mono">
                              {formatSpeed(t.speedBytesPerSec)}
                            </span>
                          )}
                        </div>
                        <Progress value={t.progressPercent} status={t.status} className="h-1.5" />
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          t.status === 'completed'
                            ? 'success'
                            : t.status === 'transferring'
                            ? 'p2p'
                            : t.status === 'connecting'
                            ? 'warning'
                            : t.status === 'failed' || t.status === 'integrity_failed'
                            ? 'danger'
                            : 'neutral'
                        }
                        size="sm"
                      >
                        {t.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                        {t.status === 'transferring' && (
                          <Button variant="ghost" size="xs" onClick={() => cancelTransfer(t.id)}>
                            Cancel
                          </Button>
                        )}
                        {(t.status === 'failed' || t.status === 'cancelled') && (
                          <Button variant="ghost" size="xs" onClick={() => retryTransfer(t.id)}>
                            Retry
                          </Button>
                        )}
                        <Button variant="ghost" size="xs" onClick={() => setSelectedTransfer(t)}>
                          Details
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </div>

      {/* Transfer Details Modal */}
      {selectedTransfer && (
        <Modal
          isOpen={!!selectedTransfer}
          onClose={() => setSelectedTransfer(null)}
          title="Transfer Diagnostics"
          description="Direct browser-to-browser WebRTC DataChannel connection telemetry"
          size="md"
        >
          <div className="space-y-4 text-xs">
            <div className="p-3.5 bg-slate-50 rounded-xl border border-slate-200/80 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-slate-500">File:</span>
                <span className="font-semibold text-slate-900">{selectedTransfer.fileName}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Size:</span>
                <span className="font-mono">{formatBytes(selectedTransfer.fileSize)}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Protocol Mode:</span>
                <span className="font-semibold text-teal-700">{selectedTransfer.mode}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Status:</span>
                <Badge variant={selectedTransfer.status === 'completed' ? 'success' : 'p2p'} size="sm">
                  {selectedTransfer.status}
                </Badge>
              </div>
            </div>

            <div className="space-y-2 bg-white p-3 border border-slate-200 rounded-xl">
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Sender Session:</span>
                <span className="font-mono text-[11px]">{selectedTransfer.sender.sessionName}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Receiver Session:</span>
                <span className="font-mono text-[11px]">{selectedTransfer.receiver.sessionName}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Started:</span>
                <span>{formatDate(selectedTransfer.createdAt)}</span>
              </div>
              {selectedTransfer.completedAt && (
                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Completed:</span>
                  <span>{formatDate(selectedTransfer.completedAt)}</span>
                </div>
              )}
            </div>

            {selectedTransfer.sha256 && (
              <div className="p-3 bg-slate-50 rounded-xl border border-slate-200">
                <div className="text-[11px] font-semibold text-slate-700 mb-1">SHA-256 Checksum:</div>
                <div className="font-mono text-[10px] text-slate-600 break-all select-all">
                  {selectedTransfer.sha256}
                </div>
              </div>
            )}

            <div className="flex justify-end pt-2">
              <Button variant="outline" size="sm" onClick={() => setSelectedTransfer(null)}>
                Close
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
