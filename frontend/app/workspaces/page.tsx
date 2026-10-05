'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { MOCK_WORKSPACES } from '@/lib/mock-data';
import { Workspace } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Avatar } from '@/components/ui/avatar';
import { Modal } from '@/components/ui/modal';
import { Input } from '@/components/ui/input';
import { Plus, ArrowRight, Users, MessageSquare, FolderOpen, ShieldCheck } from 'lucide-react';

export default function WorkspacesPage() {
  const router = useRouter();
  const { user } = useAuth();
  const [workspaces, setWorkspaces] = useState<Workspace[]>(MOCK_WORKSPACES);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [newWorkspaceName, setNewWorkspaceName] = useState('');
  const [newWorkspaceDesc, setNewWorkspaceDesc] = useState('');

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newWorkspaceName.trim()) return;

    const newWs: Workspace = {
      id: `ws_${Date.now().toString(36)}`,
      name: newWorkspaceName.trim(),
      description: newWorkspaceDesc.trim(),
      ownerId: user?.id || 'usr_theanh',
      role: 'OWNER',
      membersCount: 1,
      channelsCount: 1,
      filesCount: 0,
      createdAt: new Date().toISOString(),
    };

    setWorkspaces([newWs, ...workspaces]);
    setIsCreateOpen(false);
    setNewWorkspaceName('');
    setNewWorkspaceDesc('');
    router.push(`/w/${newWs.id}`);
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-6">
      <div className="w-full max-w-2xl">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-white text-sm">
              L
            </div>
            <div>
              <h1 className="text-lg font-bold text-slate-900">Your Workspaces</h1>
              <p className="text-xs text-slate-500">
                Logged in as <strong className="text-slate-800">{user?.name}</strong>
              </p>
            </div>
          </div>

          <Button
            variant="primary"
            size="sm"
            onClick={() => setIsCreateOpen(true)}
            leftIcon={<Plus className="w-3.5 h-3.5" />}
          >
            Create Workspace
          </Button>
        </div>

        {/* Workspace Cards Grid */}
        <div className="space-y-3">
          {workspaces.map((ws) => (
            <Link
              key={ws.id}
              href={`/w/${ws.id}`}
              className="group block bg-white p-5 rounded-xl border border-slate-200/90 shadow-card hover:border-indigo-400 hover:shadow-panel transition-all"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-start gap-4">
                  <div className="w-12 h-12 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center font-bold text-indigo-700 text-sm group-hover:bg-indigo-600 group-hover:text-white transition-colors flex-shrink-0">
                    {ws.name.substring(0, 2).toUpperCase()}
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h2 className="text-sm font-semibold text-slate-900 group-hover:text-indigo-600 transition-colors">
                        {ws.name}
                      </h2>
                      <Badge variant="neutral" size="sm">
                        {ws.role}
                      </Badge>
                    </div>
                    {ws.description && (
                      <p className="text-xs text-slate-500 mt-1 line-clamp-1">{ws.description}</p>
                    )}
                    <div className="flex items-center gap-4 mt-3 text-xs text-slate-400 font-medium">
                      <span className="flex items-center gap-1.5">
                        <Users className="w-3.5 h-3.5" />
                        <span>{ws.membersCount} members</span>
                      </span>
                      <span className="flex items-center gap-1.5">
                        <MessageSquare className="w-3.5 h-3.5" />
                        <span>{ws.channelsCount} channels</span>
                      </span>
                      <span className="flex items-center gap-1.5">
                        <FolderOpen className="w-3.5 h-3.5" />
                        <span>{ws.filesCount} files</span>
                      </span>
                    </div>
                  </div>
                </div>

                <div className="w-8 h-8 rounded-full flex items-center justify-center text-slate-400 group-hover:text-indigo-600 group-hover:translate-x-1 transition-all">
                  <ArrowRight className="w-4 h-4" />
                </div>
              </div>
            </Link>
          ))}
        </div>
      </div>

      {/* Create Workspace Modal */}
      <Modal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        title="Create a New Workspace"
        description="A dedicated collaboration space for your team, project, or organization"
        size="md"
      >
        <form onSubmit={handleCreate} className="space-y-4">
          <Input
            label="Workspace Name"
            value={newWorkspaceName}
            onChange={(e) => setNewWorkspaceName(e.target.value)}
            placeholder="e.g. Acme Engineering or Design Team"
            required
          />
          <Input
            label="Description (Optional)"
            value={newWorkspaceDesc}
            onChange={(e) => setNewWorkspaceDesc(e.target.value)}
            placeholder="What is this workspace used for?"
          />
          <div className="flex items-center justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" type="button" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" size="sm" type="submit">
              Create & Launch
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
