'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { Modal } from '@/components/ui/modal';
import { MOCK_ADMIN_WORKSPACES } from '@/lib/mock-data';
import { AdminWorkspaceItem } from '@/lib/types';
import { Layers, ShieldCheck, Eye } from 'lucide-react';

export default function AdminWorkspacesPage() {
  const [workspaces, setWorkspaces] = useState<AdminWorkspaceItem[]>(MOCK_ADMIN_WORKSPACES);
  const [inspectTarget, setInspectTarget] = useState<AdminWorkspaceItem | null>(null);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="Workspaces Registry"
        subtitle="Server-wide workspace metadata inspection (System Admin is not automatically a member)"
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-6xl mx-auto space-y-4">
          <div className="border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[28%]">Workspace</TableHead>
                  <TableHead className="w-[18%]">Owner</TableHead>
                  <TableHead className="w-[10%]">Members</TableHead>
                  <TableHead className="w-[10%]">Channels</TableHead>
                  <TableHead className="w-[10%]">Files</TableHead>
                  <TableHead className="w-[12%]">Created</TableHead>
                  <TableHead className="w-[12%] text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {workspaces.map((ws) => (
                  <TableRow key={ws.id}>
                    <TableCell>
                      <div className="flex items-center gap-2.5">
                        <div className="w-7 h-7 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center font-bold text-indigo-700 text-xs">
                          {ws.name.substring(0, 2).toUpperCase()}
                        </div>
                        <span className="font-semibold text-slate-900 text-xs">{ws.name}</span>
                      </div>
                    </TableCell>

                    <TableCell className="text-xs text-slate-700">{ws.ownerName}</TableCell>

                    <TableCell className="text-xs font-mono text-slate-600">
                      {ws.membersCount}
                    </TableCell>

                    <TableCell className="text-xs font-mono text-slate-600">
                      {ws.channelsCount}
                    </TableCell>

                    <TableCell className="text-xs font-mono text-slate-600">
                      {ws.filesCount}
                    </TableCell>

                    <TableCell className="text-xs text-slate-500">{ws.createdAt}</TableCell>

                    <TableCell className="text-right">
                      <Button variant="ghost" size="xs" onClick={() => setInspectTarget(ws)}>
                        Inspect
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      </div>

      {/* Inspect Metadata Modal */}
      {inspectTarget && (
        <Modal
          isOpen={!!inspectTarget}
          onClose={() => setInspectTarget(null)}
          title="Workspace Metadata"
          description="System-level metadata for this organization space"
          size="md"
        >
          <div className="space-y-3 text-xs">
            <div className="p-3.5 bg-slate-50 border border-slate-200 rounded-xl space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Workspace ID:</span>
                <span className="font-mono text-slate-800">{inspectTarget.id}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Name:</span>
                <span className="font-semibold text-slate-900">{inspectTarget.name}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Creator / Owner:</span>
                <span className="font-medium text-slate-800">{inspectTarget.ownerName}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">State:</span>
                <Badge variant="success" size="sm">
                  {inspectTarget.status}
                </Badge>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2 text-center p-3 bg-white border border-slate-200 rounded-xl font-mono">
              <div>
                <div className="text-base font-bold text-slate-900">{inspectTarget.membersCount}</div>
                <div className="text-[10px] text-slate-400 font-sans">Members</div>
              </div>
              <div>
                <div className="text-base font-bold text-slate-900">{inspectTarget.channelsCount}</div>
                <div className="text-[10px] text-slate-400 font-sans">Channels</div>
              </div>
              <div>
                <div className="text-base font-bold text-slate-900">{inspectTarget.filesCount}</div>
                <div className="text-[10px] text-slate-400 font-sans">Files</div>
              </div>
            </div>

            <p className="text-[11px] text-slate-400 italic">
              Notice: Privacy boundaries prevent administrators from reading private workspace
              chat messages unless explicitly invited.
            </p>

            <div className="flex justify-end pt-2">
              <Button variant="outline" size="sm" onClick={() => setInspectTarget(null)}>
                Close
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}
