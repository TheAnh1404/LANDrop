'use client';

import React, { useState, useRef } from 'react';
import { Modal } from '../ui/modal';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Avatar } from '../ui/avatar';
import { StatusDot } from '../ui/badge';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { useAuth } from '@/lib/auth/auth-context';
import { MOCK_USERS, MOCK_SESSIONS } from '@/lib/mock-data';
import { User, DeviceSession } from '@/lib/types';
import { formatBytes } from '@/lib/utils';
import { FileIcon } from '../ui/file-icon';
import {
  UploadCloud,
  Zap,
  ShieldCheck,
  CheckCircle2,
  ArrowRight,
  Laptop,
  Network,
  Share2,
} from 'lucide-react';

export function SendFileModal() {
  const { isSendModalOpen, closeSendModal, selectedFileToSend, startSendTransfer } = useTransfer();
  const { user: currentUser } = useAuth();

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<{ name: string; size: number; type?: string } | null>(
    selectedFileToSend || {
      name: 'architecture-assets.zip',
      size: 8400000000, // 8.4 GB default signature demo
      type: 'zip',
    }
  );

  // Available recipients (exclude current user)
  const recipients = MOCK_USERS.filter((u) => u.id !== currentUser?.id);
  const [selectedRecipient, setSelectedRecipient] = useState<User>(recipients[0]);

  // Recipient's available browser sessions
  const recipientSessions = MOCK_SESSIONS.filter((s) => s.userId === selectedRecipient.id);
  const [selectedSession, setSelectedSession] = useState<DeviceSession>(
    recipientSessions[0] || MOCK_SESSIONS[2]
  );

  const [isSending, setIsSending] = useState(false);

  // Update session selection when recipient changes
  const handleSelectRecipient = (recipient: User) => {
    setSelectedRecipient(recipient);
    const sessions = MOCK_SESSIONS.filter((s) => s.userId === recipient.id);
    if (sessions.length > 0) {
      setSelectedSession(sessions[0]);
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const selected = e.target.files[0];
      setFile({
        name: selected.name,
        size: selected.size,
        type: selected.name.split('.').pop() || 'bin',
      });
    }
  };

  const handleSend = async () => {
    if (!file || !selectedRecipient || !selectedSession) return;
    setIsSending(true);
    try {
      await startSendTransfer(file, selectedRecipient, selectedSession);
      closeSendModal();
    } finally {
      setIsSending(false);
    }
  };

  return (
    <Modal
      isOpen={isSendModalOpen}
      onClose={closeSendModal}
      title="Direct P2P File Transfer"
      description="Send files directly from browser to browser using WebRTC DataChannel (No server relay)"
      size="lg"
    >
      <div className="space-y-5">
        {/* Step 1: Selected File */}
        <div>
          <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
            1. File to Transfer
          </div>
          {file ? (
            <div className="flex items-center justify-between p-3.5 bg-slate-50 border border-slate-200 rounded-xl shadow-subtle">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-10 h-10 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                  <FileIcon type={file.type} className="w-5 h-5 text-indigo-600" />
                </div>
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-slate-900 truncate" title={file.name}>
                    {file.name}
                  </div>
                  <div className="text-[11px] text-slate-500 font-mono mt-0.5">
                    {formatBytes(file.size)}
                  </div>
                </div>
              </div>
              <Button
                variant="outline"
                size="xs"
                onClick={() => fileInputRef.current?.click()}
              >
                Change File
              </Button>
            </div>
          ) : (
            <div
              onClick={() => fileInputRef.current?.click()}
              className="cursor-pointer border-2 border-dashed border-slate-200 hover:border-indigo-400 p-6 rounded-xl bg-slate-50/50 flex flex-col items-center justify-center text-center transition-colors"
            >
              <UploadCloud className="w-8 h-8 text-slate-400 mb-2" />
              <div className="text-xs font-semibold text-slate-700">Choose a file or drop here</div>
              <div className="text-[11px] text-slate-400 mt-0.5">
                Chunked streaming handles multi-gigabyte files (up to 50 GB+) seamlessly
              </div>
            </div>
          )}
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            onChange={handleFileChange}
          />
        </div>

        {/* Step 2: Choose Recipient */}
        <div>
          <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
            2. Choose Recipient
          </div>
          <div className="grid grid-cols-3 gap-2">
            {recipients.map((rec) => {
              const isSelected = selectedRecipient?.id === rec.id;
              return (
                <button
                  key={rec.id}
                  type="button"
                  onClick={() => handleSelectRecipient(rec)}
                  className={`p-2.5 rounded-xl border text-left flex items-center gap-2.5 transition-all focus-ring ${
                    isSelected
                      ? 'border-indigo-600 bg-indigo-50/60 ring-1 ring-indigo-600 shadow-sm'
                      : 'border-slate-200 bg-white hover:border-slate-300'
                  }`}
                >
                  <Avatar name={rec.name} size="sm" status={rec.status} />
                  <div className="min-w-0">
                    <div className="text-xs font-semibold text-slate-900 truncate">{rec.name}</div>
                    <div className="text-[10px] text-slate-500 capitalize">{rec.status}</div>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Step 3: Available Browser/Device Sessions */}
        <div>
          <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
            3. Recipient Browser Session / Device
          </div>
          <div className="space-y-1.5">
            {recipientSessions.map((session) => {
              const isSelected = selectedSession?.id === session.id;
              return (
                <button
                  key={session.id}
                  type="button"
                  onClick={() => setSelectedSession(session)}
                  className={`w-full p-3 rounded-xl border text-left flex items-center justify-between transition-all focus-ring ${
                    isSelected
                      ? 'border-teal-600 bg-teal-50/50 ring-1 ring-teal-600 shadow-sm'
                      : 'border-slate-200 bg-white hover:border-slate-300'
                  }`}
                >
                  <div className="flex items-center gap-3">
                    <StatusDot status={session.isOnline ? 'online' : 'offline'} />
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-slate-900">
                          {selectedRecipient.name} — {session.browser} on {session.os}
                        </span>
                        {session.isOnline && session.p2pCapable && (
                          <Badge variant="p2p" size="sm">
                            <Zap className="w-2.5 h-2.5 text-teal-600" />
                            P2P available • Recommended
                          </Badge>
                        )}
                      </div>
                      <div className="text-[11px] text-slate-500 mt-0.5">
                        Device: {session.deviceName} • IP: {session.ipAddress} • Last active:{' '}
                        {session.lastSeen}
                      </div>
                    </div>
                  </div>
                  {isSelected && <CheckCircle2 className="w-4 h-4 text-teal-600 flex-shrink-0" />}
                </button>
              );
            })}
          </div>
        </div>

        {/* Visual Architecture Diagram */}
        <div className="p-3.5 bg-slate-900 text-white rounded-xl space-y-2">
          <div className="flex items-center justify-between text-xs">
            <span className="text-slate-300 flex items-center gap-1.5 font-medium">
              <Zap className="w-3.5 h-3.5 text-teal-400" />
              Direct WebRTC DataChannel Stream
            </span>
            <span className="text-[10px] text-teal-400 font-mono">LAN Direct</span>
          </div>

          <div className="flex items-center justify-between py-2 px-3 bg-slate-800 rounded-lg text-xs font-mono">
            <span className="truncate max-w-[130px] text-slate-200">
              You ({currentUser?.name})
            </span>
            <span className="text-teal-400 flex items-center gap-1 text-[11px]">
              <span className="h-px w-8 bg-teal-400/60" />
              <span>P2P</span>
              <span className="h-px w-8 bg-teal-400/60" />
            </span>
            <span className="truncate max-w-[130px] text-slate-200">
              {selectedRecipient.name}
            </span>
          </div>

          <p className="text-[11px] text-slate-400 leading-relaxed">
            The file is streamed directly between browser sessions using 64 KB binary chunks with
            backpressure control. <strong>The LANDrop Server never stores the binary file.</strong>
          </p>
        </div>

        {/* Modal Actions */}
        <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
          <Button variant="outline" size="sm" onClick={closeSendModal}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={handleSend}
            isLoading={isSending}
            disabled={!file || !selectedSession}
            leftIcon={<Zap className="w-3.5 h-3.5" />}
          >
            Start P2P Transfer
          </Button>
        </div>
      </div>
    </Modal>
  );
}
