'use client';

import React from 'react';
import { Modal } from '../ui/modal';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { FileIcon } from '../ui/file-icon';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { formatBytes } from '@/lib/utils';
import { Zap, Download, ShieldCheck } from 'lucide-react';

export function IncomingTransferDialog() {
  const { incomingTransfer, acceptIncomingTransfer, declineIncomingTransfer } = useTransfer();

  if (!incomingTransfer) return null;

  return (
    <Modal
      isOpen={!!incomingTransfer}
      onClose={() => declineIncomingTransfer(incomingTransfer.id)}
      title="Incoming Direct P2P Transfer"
      description="A team member is requesting to transfer a file directly to your browser"
      size="md"
      showCloseButton={false}
    >
      <div className="space-y-4">
        {/* File Preview Card */}
        <div className="p-4 bg-slate-50 border border-slate-200 rounded-xl flex items-center gap-3.5">
          <div className="w-12 h-12 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
            <FileIcon type={incomingTransfer.fileType} className="w-6 h-6 text-indigo-600" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-sm font-semibold text-slate-900 truncate" title={incomingTransfer.fileName}>
              {incomingTransfer.fileName}
            </div>
            <div className="text-xs text-slate-500 font-mono mt-0.5">
              {formatBytes(incomingTransfer.fileSize)}
            </div>
          </div>
        </div>

        {/* Sender and Session Details */}
        <div className="space-y-2 text-xs bg-white border border-slate-200/80 rounded-xl p-3.5">
          <div className="flex items-center justify-between">
            <span className="text-slate-500">From:</span>
            <span className="font-semibold text-slate-900">{incomingTransfer.sender.userName}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-slate-500">Session:</span>
            <span className="text-slate-700 font-mono">{incomingTransfer.sender.sessionName}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-slate-500">Method:</span>
            <Badge variant="p2p" size="sm">
              <Zap className="w-2.5 h-2.5 text-teal-600" />
              Direct P2P (WebRTC DataChannel)
            </Badge>
          </div>
          <div className="flex items-center justify-between pt-1 border-t border-slate-100">
            <span className="text-slate-500">Security:</span>
            <span className="text-emerald-700 font-medium flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5" />
              End-to-End verified
            </span>
          </div>
        </div>

        <p className="text-[11px] text-slate-500 leading-relaxed">
          The file will stream directly between browsers. Once complete, it will be automatically
          verified with SHA-256 and saved locally.
        </p>

        {/* Actions */}
        <div className="flex items-center justify-end gap-2.5 pt-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => declineIncomingTransfer(incomingTransfer.id)}
          >
            Decline
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={() => acceptIncomingTransfer(incomingTransfer.id)}
            leftIcon={<Download className="w-3.5 h-3.5" />}
          >
            Accept Transfer
          </Button>
        </div>
      </div>
    </Modal>
  );
}
