'use client';

import React, { useState } from 'react';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Avatar } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { StatusDot } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { Modal } from '@/components/ui/modal';
import { ConfirmDialog } from '@/components/ui/modal';
import { Input } from '@/components/ui/input';
import { MOCK_MEMBERS, MOCK_USERS, MOCK_SESSIONS } from '@/lib/mock-data';
import { WorkspaceMember, WorkspaceRole } from '@/lib/types';
import { formatDate } from '@/lib/utils';
import { UserPlus, MoreVertical, Shield, UserMinus, ShieldAlert, Check } from 'lucide-react';

export default function WorkspaceMembersPage({
  params,
}: {
  params: { workspaceId: string };
}) {
  const [members, setMembers] = useState<WorkspaceMember[]>(
    MOCK_MEMBERS[params.workspaceId] || MOCK_MEMBERS.ws_dev || []
  );

  const [isInviteOpen, setIsInviteOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<WorkspaceRole>('MEMBER');

  const [roleChangeTarget, setRoleChangeTarget] = useState<WorkspaceMember | null>(null);
  const [newRole, setNewRole] = useState<WorkspaceRole>('MEMBER');

  const [removeTarget, setRemoveTarget] = useState<WorkspaceMember | null>(null);

  const handleInvite = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inviteEmail.trim()) return;

    const newMember: WorkspaceMember = {
      id: `wm_${Date.now()}`,
      workspaceId: params.workspaceId,
      userId: `usr_${Date.now()}`,
      name: inviteEmail.split('@')[0],
      email: inviteEmail.trim(),
      role: inviteRole,
      status: 'offline',
      activeSessions: [],
      joinedAt: new Date().toISOString(),
    };

    setMembers([...members, newMember]);
    setIsInviteOpen(false);
    setInviteEmail('');
  };

  const handleSaveRole = () => {
    if (!roleChangeTarget) return;
    setMembers((prev) =>
      prev.map((m) => (m.id === roleChangeTarget.id ? { ...m, role: newRole } : m))
    );
    setRoleChangeTarget(null);
  };

  const handleRemoveMember = () => {
    if (!removeTarget) return;
    setMembers((prev) => prev.filter((m) => m.id !== removeTarget.id));
    setRemoveTarget(null);
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <WorkspaceTopBar
        title="Workspace Members"
        subtitle="Manage member access, workspace roles, and browser session status"
        actions={
          <Button
            variant="primary"
            size="xs"
            onClick={() => setIsInviteOpen(true)}
            leftIcon={<UserPlus className="w-3.5 h-3.5" />}
          >
            Invite Member
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-5xl mx-auto space-y-4">
          <div className="flex items-center justify-between">
            <div className="text-xs text-slate-500">
              Showing {members.length} team members enrolled in this workspace
            </div>
          </div>

          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[32%]">Member</TableHead>
                  <TableHead className="w-[20%]">Email</TableHead>
                  <TableHead className="w-[18%]">Workspace Role</TableHead>
                  <TableHead className="w-[18%]">Device / Status</TableHead>
                  <TableHead className="w-[12%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.map((member) => (
                  <TableRow key={member.id}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar name={member.name} size="sm" status={member.status} />
                        <div>
                          <div className="font-semibold text-slate-900 text-xs">{member.name}</div>
                          <div className="text-[10px] text-slate-400">
                            Joined {formatDate(member.joinedAt)}
                          </div>
                        </div>
                      </div>
                    </TableCell>

                    <TableCell className="text-xs text-slate-600 font-mono">
                      {member.email}
                    </TableCell>

                    <TableCell>
                      <Badge
                        variant={
                          member.role === 'OWNER'
                            ? 'default'
                            : member.role === 'ADMIN'
                            ? 'warning'
                            : 'neutral'
                        }
                        size="sm"
                      >
                        {member.role}
                      </Badge>
                    </TableCell>

                    <TableCell>
                      <div className="flex items-center gap-2">
                        <StatusDot status={member.status} />
                        <span className="text-xs text-slate-600 truncate max-w-[150px]">
                          {member.activeSessions[0]
                            ? `${member.activeSessions[0].browser.split(' ')[0]} on ${member.activeSessions[0].os.split(' ')[0]}`
                            : 'Offline'}
                        </span>
                      </div>
                    </TableCell>

                    <TableCell className="text-right">
                      {member.role !== 'OWNER' && (
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={() => {
                              setRoleChangeTarget(member);
                              setNewRole(member.role);
                            }}
                          >
                            Role
                          </Button>
                          <Button
                            variant="ghost"
                            size="xs"
                            className="text-red-600 hover:text-red-700 hover:bg-red-50"
                            onClick={() => setRemoveTarget(member)}
                          >
                            Remove
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      </div>

      {/* Invite Member Modal */}
      <Modal
        isOpen={isInviteOpen}
        onClose={() => setIsInviteOpen(false)}
        title="Invite Team Member"
        description="Grant access to this workspace and its peer-to-peer file transfers"
        size="sm"
      >
        <form onSubmit={handleInvite} className="space-y-4">
          <Input
            label="Email Address"
            type="email"
            value={inviteEmail}
            onChange={(e) => setInviteEmail(e.target.value)}
            placeholder="colleague@company.com"
            required
            autoFocus
          />

          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1.5">
              Workspace Role
            </label>
            <div className="grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setInviteRole('MEMBER')}
                className={`p-2.5 rounded-lg border text-left text-xs transition-colors ${
                  inviteRole === 'MEMBER'
                    ? 'border-indigo-600 bg-indigo-50 font-medium text-indigo-900'
                    : 'border-slate-200 hover:bg-slate-50 text-slate-700'
                }`}
              >
                <div>MEMBER</div>
                <div className="text-[10px] text-slate-400 mt-0.5">Read, chat, send P2P files</div>
              </button>
              <button
                type="button"
                onClick={() => setInviteRole('ADMIN')}
                className={`p-2.5 rounded-lg border text-left text-xs transition-colors ${
                  inviteRole === 'ADMIN'
                    ? 'border-indigo-600 bg-indigo-50 font-medium text-indigo-900'
                    : 'border-slate-200 hover:bg-slate-50 text-slate-700'
                }`}
              >
                <div>ADMIN</div>
                <div className="text-[10px] text-slate-400 mt-0.5">Manage channels & members</div>
              </button>
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" type="button" onClick={() => setIsInviteOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" size="sm" type="submit">
              Send Invitation
            </Button>
          </div>
        </form>
      </Modal>

      {/* Change Role Modal */}
      <Modal
        isOpen={!!roleChangeTarget}
        onClose={() => setRoleChangeTarget(null)}
        title="Change Member Role"
        description={`Update role for ${roleChangeTarget?.name}`}
        size="sm"
      >
        <div className="space-y-4">
          <div className="space-y-2">
            {(['MEMBER', 'ADMIN'] as WorkspaceRole[]).map((r) => (
              <button
                key={r}
                type="button"
                onClick={() => setNewRole(r)}
                className={`w-full p-3 rounded-lg border text-left flex items-center justify-between text-xs transition-colors ${
                  newRole === r
                    ? 'border-indigo-600 bg-indigo-50 font-semibold text-indigo-900'
                    : 'border-slate-200 hover:bg-slate-50 text-slate-700'
                }`}
              >
                <span>{r}</span>
                {newRole === r && <Check className="w-4 h-4 text-indigo-600" />}
              </button>
            ))}
          </div>

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setRoleChangeTarget(null)}>
              Cancel
            </Button>
            <Button variant="primary" size="sm" onClick={handleSaveRole}>
              Save Role
            </Button>
          </div>
        </div>
      </Modal>

      {/* Remove Member Confirm */}
      <ConfirmDialog
        isOpen={!!removeTarget}
        onClose={() => setRemoveTarget(null)}
        onConfirm={handleRemoveMember}
        title="Remove Member"
        description={`Are you sure you want to remove ${removeTarget?.name} from this workspace? They will lose access to all channels and shared files.`}
        confirmText="Remove"
        variant="danger"
      />
    </div>
  );
}
