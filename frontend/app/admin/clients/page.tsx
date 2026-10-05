'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Badge } from '@/components/ui/badge';
import { StatusDot } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/modal';
import { MOCK_ADMIN_CLIENTS } from '@/lib/mock-data';
import { AdminClientSession } from '@/lib/types';
import { Monitor, WifiOff, RefreshCw } from 'lucide-react';

export default function AdminClientsPage() {
  const [clients, setClients] = useState<AdminClientSession[]>(MOCK_ADMIN_CLIENTS);
  const [disconnectTarget, setDisconnectTarget] = useState<AdminClientSession | null>(null);

  const handleDisconnect = () => {
    if (!disconnectTarget) return;
    setClients((prev) => prev.filter((c) => c.id !== disconnectTarget.id));
    setDisconnectTarget(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="Connected Browser Clients"
        subtitle="Active WebSocket sockets and WebRTC signaling sessions"
        actions={
          <Badge variant="p2p" size="sm">
            {clients.length} Active Sockets
          </Badge>
        }
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-6xl mx-auto space-y-4">
          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[20%]">User</TableHead>
                  <TableHead className="w-[18%]">Browser / Device</TableHead>
                  <TableHead className="w-[14%]">IP Address</TableHead>
                  <TableHead className="w-[12%]">Connection ID</TableHead>
                  <TableHead className="w-[14%]">Protocol</TableHead>
                  <TableHead className="w-[12%]">Heartbeat</TableHead>
                  <TableHead className="w-[10%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {clients.map((cli) => (
                  <TableRow key={cli.id}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <StatusDot status={cli.status === 'online' ? 'online' : 'offline'} />
                        <span className="font-semibold text-slate-900 text-xs">
                          {cli.userName || 'Unauthenticated'}
                        </span>
                      </div>
                    </TableCell>

                    <TableCell className="text-xs text-slate-600">
                      <div>
                        {cli.browser} / {cli.os}
                      </div>
                      <div className="text-[10px] text-slate-400 font-mono">{cli.browserVersion}</div>
                    </TableCell>

                    <TableCell className="text-xs font-mono text-slate-600">
                      {cli.ipAddress}
                    </TableCell>

                    <TableCell className="text-xs font-mono text-slate-500">
                      {cli.connectionId}
                    </TableCell>

                    <TableCell className="text-xs font-mono text-teal-700 font-medium">
                      {cli.protocol}
                    </TableCell>

                    <TableCell className="text-xs text-slate-500">{cli.lastHeartbeat}</TableCell>

                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="xs"
                        className="text-red-600 hover:text-red-700 hover:bg-red-50"
                        onClick={() => setDisconnectTarget(cli)}
                      >
                        Disconnect
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      </div>

      {/* Disconnect Confirm */}
      <ConfirmDialog
        isOpen={!!disconnectTarget}
        onClose={() => setDisconnectTarget(null)}
        onConfirm={handleDisconnect}
        title="Disconnect Client"
        description={`Terminate socket connection ${disconnectTarget?.connectionId} (${disconnectTarget?.userName || 'Unauthenticated'})?`}
        confirmText="Disconnect"
        variant="danger"
      />
    </div>
  );
}
