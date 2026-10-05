'use client';

import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { FileTransfer } from '../types';
import { MOCK_TRANSFERS } from '../mock-data';
import { useSession } from '../session/session-provider';

type Transfers = { transfers: FileTransfer[]; startDemo: (workspaceId: string) => void; cancelTransfer: (id: string) => void };
const Context = createContext<Transfers | null>(null);
export function DemoTransferProvider({ children }: { children: React.ReactNode }) {
  const { mode, user } = useSession();
  const [transfers, setTransfers] = useState<FileTransfer[]>([]);
  const timers = useRef(new Map<string, ReturnType<typeof setInterval>>());
  useEffect(() => {
    timers.current.forEach(clearInterval); timers.current.clear();
    setTransfers(mode === 'demo' && user ? MOCK_TRANSFERS.map(transfer => ({ ...transfer })) : []);
    return () => { timers.current.forEach(clearInterval); timers.current.clear(); };
  }, [mode, user?.id]);
  const startDemo = (workspaceId: string) => {
    if (mode !== 'demo' || !user) return;
    const id = `demo_${crypto.randomUUID()}`;
    const transfer: FileTransfer = { id, workspaceId, fileName: 'ban-thiet-ke.zip', fileSize: 64 * 1024 * 1024, sender: { userId: user.id, userName: user.name, sessionName: 'Trình duyệt demo' }, receiver: { userId: 'demo_recipient', userName: 'Thành viên mẫu', sessionName: 'Thiết bị mẫu' }, mode: 'LAN Direct', status: 'transferring', transferredBytes: 0, speedBytesPerSec: 0, progressPercent: 0, etaSeconds: 0, createdAt: new Date().toISOString() };
    setTransfers(previous => [transfer, ...previous]);
    let progress = 0;
    const timer = setInterval(() => {
      progress = Math.min(progress + 10, 100);
      setTransfers(previous => previous.map(item => item.id === id ? { ...item, progressPercent: progress, transferredBytes: Math.round(item.fileSize * progress / 100), status: progress === 100 ? 'completed' : 'transferring' } : item));
      if (progress === 100) { clearInterval(timer); timers.current.delete(id); }
    }, 650);
    timers.current.set(id, timer);
  };
  const cancelTransfer = (id: string) => {
    const timer = timers.current.get(id);
    if (timer) clearInterval(timer);
    timers.current.delete(id);
    setTransfers(previous => previous.map(item => item.id === id ? { ...item, status: 'cancelled' } : item));
  };
  return <Context.Provider value={{ transfers, startDemo, cancelTransfer }}>{children}</Context.Provider>;
}
export function useDemoTransfers() { const context = useContext(Context); if (!context) throw new Error('DemoTransferProvider is required.'); return context; }
