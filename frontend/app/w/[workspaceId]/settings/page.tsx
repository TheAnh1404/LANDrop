'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Tabs } from '@/components/ui/file-icon';
import { ConfirmDialog } from '@/components/ui/modal';
import { MOCK_WORKSPACES, MOCK_CHANNELS } from '@/lib/mock-data';
import { Settings, Users, Hash, Shield, AlertTriangle, CheckCircle2 } from 'lucide-react';

export default function WorkspaceSettingsPage({
  params,
}: {
  params: { workspaceId: string };
}) {
  const router = useRouter();
  const workspace =
    MOCK_WORKSPACES.find((w) => w.id === params.workspaceId) || MOCK_WORKSPACES[0];
  const channels = MOCK_CHANNELS[params.workspaceId] || MOCK_CHANNELS.ws_dev || [];

  const [activeTab, setActiveTab] = useState('general');
  const [name, setName] = useState(workspace.name);
  const [description, setDescription] = useState(workspace.description || '');
  const [isSaved, setIsSaved] = useState(false);

  // Danger zone dialogs
  const [isLeaveOpen, setIsLeaveOpen] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);

  const tabs = [
    { id: 'general', label: 'General', icon: <Settings className="w-3.5 h-3.5" /> },
    { id: 'channels', label: 'Channels', icon: <Hash className="w-3.5 h-3.5" /> },
    { id: 'permissions', label: 'Permissions & P2P', icon: <Shield className="w-3.5 h-3.5" /> },
  ];

  const handleSaveGeneral = (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaved(true);
    setTimeout(() => setIsSaved(false), 2000);
  };

  const handleLeave = () => {
    setIsLeaveOpen(false);
    router.push('/workspaces');
  };

  const handleDelete = () => {
    setIsDeleteOpen(false);
    router.push('/workspaces');
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-slate-50">
      <WorkspaceTopBar title="Workspace Settings" subtitle="Configure workspace preferences, channels, and security" />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-4xl mx-auto space-y-6">
          {/* Settings Tabs */}
          <div className="bg-white rounded-xl border border-slate-200/80 px-4 pt-2 shadow-subtle">
            <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
          </div>

          {/* Tab 1: General */}
          {activeTab === 'general' && (
            <div className="space-y-6">
              <Card>
                <CardHeader>
                  <CardTitle>Workspace Profile</CardTitle>
                  <CardDescription>
                    Basic display settings for this collaborative environment
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <form onSubmit={handleSaveGeneral} className="space-y-4">
                    <div className="flex items-center gap-4">
                      <div className="w-14 h-14 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-white text-lg shadow-sm">
                        {name.substring(0, 2).toUpperCase()}
                      </div>
                      <div className="text-xs text-slate-500">
                        <div className="font-semibold text-slate-700">Workspace Icon</div>
                        <div>Generated automatically from the workspace title.</div>
                      </div>
                    </div>

                    <Input
                      label="Workspace Name"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                      required
                    />

                    <Input
                      label="Description"
                      value={description}
                      onChange={(e) => setDescription(e.target.value)}
                      placeholder="Brief summary of workspace purpose"
                    />

                    <div className="flex items-center justify-end gap-2 pt-2">
                      {isSaved && (
                        <span className="text-xs text-emerald-600 font-medium flex items-center gap-1">
                          <CheckCircle2 className="w-3.5 h-3.5" /> Saved
                        </span>
                      )}
                      <Button variant="primary" size="sm" type="submit">
                        Save Changes
                      </Button>
                    </div>
                  </form>
                </CardContent>
              </Card>

              {/* Danger Zone */}
              <div className="bg-white rounded-xl border border-red-200/80 shadow-card overflow-hidden">
                <div className="px-5 py-4 border-b border-red-100 bg-red-50/50 flex items-center gap-2 text-red-900">
                  <AlertTriangle className="w-4 h-4 text-red-600" />
                  <h3 className="text-sm font-semibold">Danger Zone</h3>
                </div>
                <div className="p-5 space-y-4 text-xs divide-y divide-slate-100">
                  <div className="flex items-center justify-between pb-3">
                    <div>
                      <div className="font-semibold text-slate-900">Leave Workspace</div>
                      <div className="text-slate-500 mt-0.5">
                        You will immediately lose access to channels, files, and direct peer transfers.
                      </div>
                    </div>
                    <Button variant="outline" size="sm" onClick={() => setIsLeaveOpen(true)}>
                      Leave
                    </Button>
                  </div>

                  <div className="flex items-center justify-between pt-3">
                    <div>
                      <div className="font-semibold text-red-600">Delete Workspace</div>
                      <div className="text-slate-500 mt-0.5">
                        Permanently deletes this workspace and all associated channel messages.
                      </div>
                    </div>
                    <Button variant="danger" size="sm" onClick={() => setIsDeleteOpen(true)}>
                      Delete Workspace
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Tab 2: Channels */}
          {activeTab === 'channels' && (
            <Card>
              <CardHeader>
                <CardTitle>Manage Channels</CardTitle>
                <CardDescription>
                  Review and configure text channels for this workspace
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                {channels.map((ch) => (
                  <div
                    key={ch.id}
                    className="flex items-center justify-between p-3.5 border border-slate-200 rounded-xl"
                  >
                    <div className="flex items-center gap-2.5">
                      <Hash className="w-4 h-4 text-indigo-600" />
                      <div>
                        <div className="text-xs font-semibold text-slate-900">#{ch.name}</div>
                        <div className="text-[11px] text-slate-500">{ch.topic || 'No topic'}</div>
                      </div>
                    </div>
                    <span className="text-[11px] text-slate-400 font-mono">
                      {ch.isPrivate ? 'Private' : 'Public'}
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>
          )}

          {/* Tab 3: Permissions & P2P */}
          {activeTab === 'permissions' && (
            <Card>
              <CardHeader>
                <CardTitle>Permissions & WebRTC Policy</CardTitle>
                <CardDescription>
                  Configure security boundaries for direct browser-to-browser large file streaming
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4 text-xs">
                <div className="flex items-center justify-between p-3.5 bg-slate-50 rounded-xl border border-slate-200">
                  <div>
                    <div className="font-semibold text-slate-900">Direct WebRTC DataChannel Streaming</div>
                    <div className="text-slate-500 mt-0.5">
                      Enables browser-to-browser P2P file transfers without routing binary data through the server.
                    </div>
                  </div>
                  <span className="text-emerald-700 font-semibold text-xs">Enabled</span>
                </div>

                <div className="flex items-center justify-between p-3.5 bg-slate-50 rounded-xl border border-slate-200">
                  <div>
                    <div className="font-semibold text-slate-900">End-to-End SHA-256 Verification</div>
                    <div className="text-slate-500 mt-0.5">
                      Receiver browser automatically hashes file chunks upon arrival to verify integrity before saving.
                    </div>
                  </div>
                  <span className="text-emerald-700 font-semibold text-xs">Enforced</span>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </div>

      {/* Confirm Leave Dialog */}
      <ConfirmDialog
        isOpen={isLeaveOpen}
        onClose={() => setIsLeaveOpen(false)}
        onConfirm={handleLeave}
        title="Leave Workspace"
        description="Are you sure you want to leave this workspace? You will need an invite to rejoin."
        confirmText="Leave"
        variant="warning"
      />

      {/* Confirm Delete Dialog */}
      <ConfirmDialog
        isOpen={isDeleteOpen}
        onClose={() => setIsDeleteOpen(false)}
        onConfirm={handleDelete}
        title="Delete Workspace Permanently"
        description="This will permanently delete the workspace, all channel chat histories, and metadata. This cannot be undone."
        confirmText="Delete Workspace"
        variant="danger"
      />
    </div>
  );
}
