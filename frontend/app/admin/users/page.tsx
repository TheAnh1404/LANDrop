'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Avatar } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { ConfirmDialog } from '@/components/ui/modal';
import { Modal } from '@/components/ui/modal';
import { MOCK_ADMIN_USERS } from '@/lib/mock-data';
import { AdminUser } from '@/lib/types';
import { formatDate } from '@/lib/utils';
import { Users, MoreVertical, LogOut, ShieldAlert, CheckCircle2, Ban } from 'lucide-react';

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>(MOCK_ADMIN_USERS);
  const [selectedUser, setSelectedUser] = useState<AdminUser | null>(null);

  // Dialog states
  const [actionTarget, setActionTarget] = useState<AdminUser | null>(null);
  const [actionType, setActionType] = useState<'disable' | 'enable' | 'force_logout' | null>(null);

  const handleConfirmAction = () => {
    if (!actionTarget || !actionType) return;

    if (actionType === 'disable') {
      setUsers((prev) =>
        prev.map((u) => (u.id === actionTarget.id ? { ...u, status: 'disabled' } : u))
      );
    } else if (actionType === 'enable') {
      setUsers((prev) =>
        prev.map((u) => (u.id === actionTarget.id ? { ...u, status: 'active' } : u))
      );
    } else if (actionType === 'force_logout') {
      setUsers((prev) =>
        prev.map((u) => (u.id === actionTarget.id ? { ...u, sessionsCount: 0 } : u))
      );
    }

    setActionTarget(null);
    setActionType(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="User Administration"
        subtitle="Manage user accounts, authentication states, and active session permissions"
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-6xl mx-auto space-y-4">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Showing {users.length} registered accounts</span>
          </div>

          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[26%]">User</TableHead>
                  <TableHead className="w-[18%]">Email</TableHead>
                  <TableHead className="w-[10%]">Status</TableHead>
                  <TableHead className="w-[10%]">Sessions</TableHead>
                  <TableHead className="w-[10%]">Workspaces</TableHead>
                  <TableHead className="w-[12%]">Created</TableHead>
                  <TableHead className="w-[14%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar name={u.name} size="sm" />
                        <div>
                          <div className="font-semibold text-slate-900 text-xs flex items-center gap-1.5">
                            <span>{u.name}</span>
                            {u.role === 'SYSTEM_ADMIN' && (
                              <Badge variant="warning" size="sm">
                                Root
                              </Badge>
                            )}
                          </div>
                          <div className="text-[10px] text-slate-400">Last login: {u.lastLogin}</div>
                        </div>
                      </div>
                    </TableCell>

                    <TableCell className="text-xs text-slate-600 font-mono">{u.email}</TableCell>

                    <TableCell>
                      <Badge variant={u.status === 'active' ? 'success' : 'danger'} size="sm">
                        {u.status}
                      </Badge>
                    </TableCell>

                    <TableCell className="text-xs text-slate-600 font-mono">
                      {u.sessionsCount} active
                    </TableCell>

                    <TableCell className="text-xs text-slate-600 font-mono">
                      {u.workspacesCount}
                    </TableCell>

                    <TableCell className="text-xs text-slate-500">{u.createdAt}</TableCell>

                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => setSelectedUser(u)}
                        >
                          View
                        </Button>

                        {u.status === 'active' ? (
                          <Button
                            variant="ghost"
                            size="xs"
                            className="text-amber-600 hover:text-amber-700"
                            onClick={() => {
                              setActionTarget(u);
                              setActionType('disable');
                            }}
                          >
                            Disable
                          </Button>
                        ) : (
                          <Button
                            variant="ghost"
                            size="xs"
                            className="text-emerald-600 hover:text-emerald-700"
                            onClick={() => {
                              setActionTarget(u);
                              setActionType('enable');
                            }}
                          >
                            Enable
                          </Button>
                        )}

                        <Button
                          variant="ghost"
                          size="xs"
                          className="text-red-600 hover:text-red-700 hover:bg-red-50"
                          onClick={() => {
                            setActionTarget(u);
                            setActionType('force_logout');
                          }}
                        >
                          Logout
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      </div>

      {/* User Details Modal */}
      {selectedUser && (
        <Modal
          isOpen={!!selectedUser}
          onClose={() => setSelectedUser(null)}
          title="User Account Details"
          size="md"
        >
          <div className="space-y-4 text-xs">
            <div className="flex items-center gap-3 p-4 bg-slate-50 border border-slate-200 rounded-xl">
              <Avatar name={selectedUser.name} size="lg" />
              <div>
                <div className="text-sm font-semibold text-slate-900">{selectedUser.name}</div>
                <div className="text-xs text-slate-500">{selectedUser.email}</div>
                <div className="mt-1 flex gap-2">
                  <Badge variant={selectedUser.status === 'active' ? 'success' : 'danger'} size="sm">
                    {selectedUser.status}
                  </Badge>
                  <Badge variant="neutral" size="sm">
                    {selectedUser.role}
                  </Badge>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3 p-3 bg-white border border-slate-200 rounded-xl">
              <div>
                <span className="text-slate-400 block text-[11px]">Enrolled Devices</span>
                <span className="font-semibold text-slate-800">{selectedUser.devicesCount}</span>
              </div>
              <div>
                <span className="text-slate-400 block text-[11px]">Active Browser Sessions</span>
                <span className="font-semibold text-slate-800">{selectedUser.sessionsCount}</span>
              </div>
              <div>
                <span className="text-slate-400 block text-[11px]">Workspaces Joined</span>
                <span className="font-semibold text-slate-800">{selectedUser.workspacesCount}</span>
              </div>
              <div>
                <span className="text-slate-400 block text-[11px]">Last Activity</span>
                <span className="font-semibold text-slate-800">{selectedUser.lastLogin}</span>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <Button variant="outline" size="sm" onClick={() => setSelectedUser(null)}>
                Close
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Confirmation Dialogs */}
      <ConfirmDialog
        isOpen={!!actionTarget && !!actionType}
        onClose={() => {
          setActionTarget(null);
          setActionType(null);
        }}
        onConfirm={handleConfirmAction}
        title={
          actionType === 'disable'
            ? 'Disable User Account'
            : actionType === 'enable'
            ? 'Enable User Account'
            : 'Force Logout All Sessions'
        }
        description={
          actionType === 'disable'
            ? `Are you sure you want to disable ${actionTarget?.name}? They will immediately lose access to all server workspaces.`
            : actionType === 'enable'
            ? `Re-activate account for ${actionTarget?.name}?`
            : `Revoke all active browser sessions for ${actionTarget?.name}? They will be forced to re-authenticate.`
        }
        confirmText={
          actionType === 'disable' ? 'Disable' : actionType === 'enable' ? 'Enable' : 'Force Logout'
        }
        variant={actionType === 'enable' ? 'primary' : 'danger'}
      />
    </div>
  );
}
