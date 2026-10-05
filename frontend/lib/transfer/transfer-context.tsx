'use client';

import React, { createContext, useContext, useState, useEffect } from 'react';
import { FileTransfer, TransferStatus, User, DeviceSession } from '../types';
import { MOCK_TRANSFERS, CURRENT_USER, CURRENT_DEVICE_SESSION } from '../mock-data';
import { WebRTCTransferService } from '../webrtc/webrtc-transfer-service';
import { generateId } from '../utils';

interface TransferContextType {
  transfers: FileTransfer[];
  activeTransfer: FileTransfer | null;
  incomingTransfer: FileTransfer | null;
  isSendModalOpen: boolean;
  selectedFileToSend: { name: string; size: number } | null;
  openSendModal: (file?: { name: string; size: number }) => void;
  closeSendModal: () => void;
  startSendTransfer: (
    file: { name: string; size: number; type?: string },
    recipient: User,
    session: DeviceSession
  ) => Promise<string>;
  acceptIncomingTransfer: (transferId: string) => Promise<void>;
  declineIncomingTransfer: (transferId: string) => void;
  cancelTransfer: (transferId: string) => void;
  retryTransfer: (transferId: string) => void;
  dismissIncomingDialog: () => void;
  triggerSimulatedIncoming: () => void;
}

const TransferContext = createContext<TransferContextType | undefined>(undefined);

export function TransferProvider({ children }: { children: React.ReactNode }) {
  const [transfers, setTransfers] = useState<FileTransfer[]>(MOCK_TRANSFERS);
  const [activeTransferId, setActiveTransferId] = useState<string | null>('tr_p2p_active_demo');
  const [incomingTransfer, setIncomingTransfer] = useState<FileTransfer | null>(null);
  const [isSendModalOpen, setIsSendModalOpen] = useState(false);
  const [selectedFileToSend, setSelectedFileToSend] = useState<{ name: string; size: number } | null>(null);
  const [servicesMap] = useState<Map<string, WebRTCTransferService>>(new Map());

  const activeTransfer = transfers.find((t) => t.id === activeTransferId) || null;

  const openSendModal = (file?: { name: string; size: number }) => {
    if (file) setSelectedFileToSend(file);
    setIsSendModalOpen(true);
  };

  const closeSendModal = () => {
    setIsSendModalOpen(false);
    setSelectedFileToSend(null);
  };

  const startSendTransfer = async (
    file: { name: string; size: number; type?: string },
    recipient: User,
    session: DeviceSession
  ): Promise<string> => {
    const id = generateId('tr_p2p');
    const newTransfer: FileTransfer = {
      id,
      fileName: file.name,
      fileSize: file.size,
      fileType: file.type || file.name.split('.').pop() || 'bin',
      sender: {
        userId: CURRENT_USER.id,
        userName: CURRENT_USER.name,
        sessionId: CURRENT_DEVICE_SESSION.id,
        sessionName: `${CURRENT_USER.name} (${CURRENT_DEVICE_SESSION.browser.split(' ')[0]} on ${CURRENT_DEVICE_SESSION.os.split(' ')[0]})`,
      },
      receiver: {
        userId: recipient.id,
        userName: recipient.name,
        sessionId: session.id,
        sessionName: `${recipient.name} (${session.browser.split(' ')[0]} on ${session.os.split(' ')[0]})`,
      },
      mode: 'Direct P2P (WebRTC)',
      status: 'waiting',
      transferredBytes: 0,
      speedBytesPerSec: 0,
      progressPercent: 0,
      etaSeconds: 0,
      createdAt: new Date().toISOString(),
    };

    setTransfers((prev) => [newTransfer, ...prev]);
    setActiveTransferId(id);
    closeSendModal();

    // Start WebRTC service
    const service = new WebRTCTransferService(id);
    servicesMap.set(id, service);

    service.sendFile(
      file,
      (transferred, speed, percent, eta) => {
        setTransfers((prev) =>
          prev.map((t) =>
            t.id === id
              ? {
                  ...t,
                  transferredBytes: transferred,
                  speedBytesPerSec: speed,
                  progressPercent: percent,
                  etaSeconds: eta,
                }
              : t
          )
        );
      },
      (status, error) => {
        setTransfers((prev) =>
          prev.map((t) =>
            t.id === id
              ? {
                  ...t,
                  status,
                  errorMessage: error,
                  completedAt: status === 'completed' ? new Date().toISOString() : undefined,
                }
              : t
          )
        );
      }
    );

    return id;
  };

  const acceptIncomingTransfer = async (transferId: string) => {
    const transfer = transfers.find((t) => t.id === transferId) || incomingTransfer;
    if (!transfer) return;

    setIncomingTransfer(null);
    setActiveTransferId(transfer.id);

    // Update status to connecting
    setTransfers((prev) =>
      prev.map((t) => (t.id === transferId ? { ...t, status: 'connecting' as TransferStatus } : t))
    );

    const service = new WebRTCTransferService(transfer.id);
    servicesMap.set(transfer.id, service);

    service.sendFile(
      { name: transfer.fileName, size: transfer.fileSize },
      (transferred, speed, percent, eta) => {
        setTransfers((prev) =>
          prev.map((t) =>
            t.id === transferId
              ? {
                  ...t,
                  transferredBytes: transferred,
                  speedBytesPerSec: speed,
                  progressPercent: percent,
                  etaSeconds: eta,
                }
              : t
          )
        );
      },
      (status, error) => {
        setTransfers((prev) =>
          prev.map((t) =>
            t.id === transferId
              ? {
                  ...t,
                  status,
                  errorMessage: error,
                  completedAt: status === 'completed' ? new Date().toISOString() : undefined,
                }
              : t
          )
        );
      }
    );
  };

  const declineIncomingTransfer = (transferId: string) => {
    setTransfers((prev) =>
      prev.map((t) =>
        t.id === transferId
          ? {
              ...t,
              status: 'declined' as TransferStatus,
              errorMessage: 'Transfer was declined by receiver',
            }
          : t
      )
    );
    setIncomingTransfer(null);
  };

  const cancelTransfer = (transferId: string) => {
    const service = servicesMap.get(transferId);
    if (service) {
      service.cancel();
      servicesMap.delete(transferId);
    }
    setTransfers((prev) =>
      prev.map((t) =>
        t.id === transferId
          ? {
              ...t,
              status: 'cancelled' as TransferStatus,
              errorMessage: 'Cancelled by user',
              speedBytesPerSec: 0,
            }
          : t
      )
    );
  };

  const retryTransfer = (transferId: string) => {
    const transfer = transfers.find((t) => t.id === transferId);
    if (!transfer) return;

    const dummyRecipient: User = {
      id: transfer.receiver.userId,
      name: transfer.receiver.userName,
      email: '',
      status: 'online',
      createdAt: '',
    };
    const dummySession: DeviceSession = {
      id: transfer.receiver.sessionId || 'sess_retry',
      userId: transfer.receiver.userId,
      userName: transfer.receiver.userName,
      deviceName: 'Device',
      browser: 'Browser',
      os: 'OS',
      ipAddress: '192.168.1.20',
      isCurrentSession: false,
      isOnline: true,
      p2pCapable: true,
      lastSeen: 'now',
      createdAt: '',
    };

    startSendTransfer(
      { name: transfer.fileName, size: transfer.fileSize, type: transfer.fileType },
      dummyRecipient,
      dummySession
    );
  };

  const dismissIncomingDialog = () => {
    setIncomingTransfer(null);
  };

  const triggerSimulatedIncoming = () => {
    const id = generateId('tr_inc');
    const newIncoming: FileTransfer = {
      id,
      fileName: 'architecture-assets.zip',
      fileSize: 8400000000, // 8.4 GB
      fileType: 'zip',
      sender: {
        userId: 'usr_ken',
        userName: 'Ken Nguyen',
        sessionId: 'sess_ken_win',
        sessionName: 'Ken Nguyen (Chrome on Windows)',
      },
      receiver: {
        userId: CURRENT_USER.id,
        userName: CURRENT_USER.name,
        sessionId: CURRENT_DEVICE_SESSION.id,
        sessionName: `${CURRENT_USER.name} (Chrome on Windows)`,
      },
      mode: 'Direct P2P (WebRTC)',
      status: 'waiting',
      transferredBytes: 0,
      speedBytesPerSec: 0,
      progressPercent: 0,
      etaSeconds: 0,
      createdAt: new Date().toISOString(),
    };

    setTransfers((prev) => [newIncoming, ...prev]);
    setIncomingTransfer(newIncoming);
  };

  return (
    <TransferContext.Provider
      value={{
        transfers,
        activeTransfer,
        incomingTransfer,
        isSendModalOpen,
        selectedFileToSend,
        openSendModal,
        closeSendModal,
        startSendTransfer,
        acceptIncomingTransfer,
        declineIncomingTransfer,
        cancelTransfer,
        retryTransfer,
        dismissIncomingDialog,
        triggerSimulatedIncoming,
      }}
    >
      {children}
    </TransferContext.Provider>
  );
}

export function useTransfer() {
  const context = useContext(TransferContext);
  if (!context) {
    throw new Error('useTransfer must be used within a TransferProvider');
  }
  return context;
}
