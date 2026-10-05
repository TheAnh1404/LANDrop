'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/modal';
import { MOCK_ADMIN_SESSIONS } from '@/lib/mock-data';
import { AdminUserSession } from '@/lib/types';
import { KeyRound, ShieldAlert } from 'lucide-react';

export default function AdminSessionsPage() {
  const [sessions, setSessions] = useState<AdminUserSession[]>(MOCK_ADMIN_SESSIONS);
  const [revokeTarget, setRevokeTarget] = useState<AdminUserSession | null>(null);

  const handleRevoke = () => {
    if (!revokeTarget) return;
    setSessions((prev) =>
      prev.map((s) => (s.id === revokeTarget.id ? { ...s, status: 'revoked' } : s))
    );
    setRevokeTarget(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="Active User Sessions"
        subtitle="Enrolled browser sessions and authentication tokens (tokens strictly omitted for security)"
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-6xl mx-auto space-y-4">
          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[18%]">User</TableHead>
                  <TableHead className="w-[20%]">Browser / Device</TableHead>
                  <TableHead className="w-[16%]">Session ID</TableHead>
                  <TableHead className="w-[14%]">Created</TableHead>
                  <TableHead className="w-[12%]">Last Seen</TableHead>
                  <TableHead className="w-[10%]">Status</TableHead>
                  <TableHead className="w-[10%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sessions.map((sess) => (
                  <TableRow key={sess.id}>
                    <TableCell>
                      <span className="font-semibold text-slate-900 text-xs">{sess.userName}</span>
                    </TableCell>

                    <TableCell className="text-xs text-slate-600">
                      <div>{sess.deviceInfo}</div>
                      <div className="text-[10px] text-slate-400 font-mono">{sess.ipAddress}</div>
                    </TableCell>

                    <TableCell className="text-xs font-mono text-slate-500">{sess.id}</TableCell>

                    <TableCell className="text-xs text-slate-500">{sess.createdAt}</TableCell>

                    <TableCell className="text-xs text-slate-500">{sess.lastSeen}</TableCell>

                    <TableCell>
                      <Badge variant={sess.status === 'active' ? 'success' : 'danger'} size="sm">
                        {sess.status}
                      </Badge>
                    </TableCell>

                    <TableCell className="text-right">
                      {sess.status === 'active' && (
                        <Button
                          variant="ghost"
                          size="xs"
                          className="text-red-600 hover:text-red-700 hover:bg-red-50"
                          onClick={() => setRevokeTarget(sess)}
                        >
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      </div>

      <ConfirmDialog
        isOpen={!!revokeTarget}
        onClose={() => setRevokeTarget(null)}
        onConfirm={handleRevoke}
        title="Revoke Session"
        description={`Revoke browser session ${revokeTarget?.id} for user ${revokeTarget?.userName}? This will invalidate their credentials immediately.`}
        confirmText="Revoke"
        variant="danger"
      />
    </div>
  );
}
