'use client';

import React from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Avatar, AvatarGroup } from '@/components/ui/avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { FileIcon } from '@/components/ui/file-icon';
import { TransferProgressCard } from '@/components/ui/transfer-progress';
import { useAuth } from '@/lib/auth/auth-context';
import { useTransfer } from '@/lib/transfer/transfer-context';
import {
  MOCK_WORKSPACES,
  MOCK_MEMBERS,
  MOCK_MESSAGES,
  MOCK_FILES,
  MOCK_TRANSFERS,
} from '@/lib/mock-data';
import { formatBytes, formatRelativeTime } from '@/lib/utils';
import {
  Send,
  MessageSquare,
  FolderPlus,
  UserPlus,
  ArrowRight,
  Zap,
  Clock,
  ChevronRight,
  Shield,
} from 'lucide-react';

export default function WorkspaceHomePage({
  params,
}: {
  params: { workspaceId: string };
}) {
  const router = useRouter();
  const { user } = useAuth();
  const { openSendModal, transfers } = useTransfer();

  const workspace =
    MOCK_WORKSPACES.find((w) => w.id === params.workspaceId) || MOCK_WORKSPACES[0];
  const members = MOCK_MEMBERS[params.workspaceId] || MOCK_MEMBERS.ws_dev || [];
  const recentMessages = MOCK_MESSAGES.c_general || [];
  const recentFiles = (MOCK_FILES[params.workspaceId] || MOCK_FILES.ws_dev || []).slice(0, 4);
  const activeTransfers = transfers.slice(0, 2);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-slate-50">
      <WorkspaceTopBar title={workspace.name} subtitle="Workspace Overview" />

      <main className="flex-1 overflow-y-auto p-6 space-y-6">
        {/* Workspace Hero Header */}
        <div className="bg-white rounded-2xl border border-slate-200/90 p-6 shadow-card flex flex-col md:flex-row md:items-center justify-between gap-6">
          <div className="space-y-2 max-w-xl">
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-white text-base shadow-sm">
                {workspace.name.substring(0, 2).toUpperCase()}
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h1 className="text-xl font-bold text-slate-900 tracking-tight">
                    {workspace.name}
                  </h1>
                  <Badge variant="default" size="sm">
                    {workspace.role}
                  </Badge>
                </div>
                <p className="text-xs text-slate-500 mt-0.5">{workspace.description}</p>
              </div>
            </div>

            <div className="flex items-center gap-4 pt-1">
              <div className="flex items-center gap-2">
                <span className="text-xs text-slate-500 font-medium">Members:</span>
                <AvatarGroup>
                  {members.map((m) => (
                    <Avatar key={m.id} name={m.name} size="xs" status={m.status} />
                  ))}
                </AvatarGroup>
              </div>
              <span className="text-slate-300">•</span>
              <div className="text-xs text-slate-500">
                Current role:{' '}
                <strong className="text-slate-800 font-semibold">{workspace.role}</strong>
              </div>
            </div>
          </div>

          {/* Quick Actions */}
          <div className="flex flex-wrap items-center gap-2.5">
            <Button
              variant="primary"
              size="sm"
              onClick={() => openSendModal()}
              leftIcon={<Send className="w-3.5 h-3.5" />}
            >
              Send File
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => router.push(`/w/${workspace.id}/chat`)}
              leftIcon={<MessageSquare className="w-3.5 h-3.5" />}
            >
              Open Chat
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => router.push(`/w/${workspace.id}/files`)}
              leftIcon={<FolderPlus className="w-3.5 h-3.5" />}
            >
              Files
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => router.push(`/w/${workspace.id}/members`)}
              leftIcon={<UserPlus className="w-3.5 h-3.5" />}
            >
              Invite
            </Button>
          </div>
        </div>

        {/* Two Column Layout */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left 2 Cols: Recent Conversations & Files */}
          <div className="lg:col-span-2 space-y-6">
            {/* Recent Conversations */}
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between w-full">
                  <div className="flex items-center gap-2">
                    <MessageSquare className="w-4 h-4 text-indigo-600" />
                    <CardTitle>Recent Conversations</CardTitle>
                  </div>
                  <Link
                    href={`/w/${workspace.id}/chat`}
                    className="text-xs text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1"
                  >
                    <span>View all channels</span>
                    <ChevronRight className="w-3.5 h-3.5" />
                  </Link>
                </div>
              </CardHeader>
              <CardContent className="p-0 divide-y divide-slate-100">
                {recentMessages.map((msg) => (
                  <div key={msg.id} className="p-4 hover:bg-slate-50/60 transition-colors flex items-start gap-3">
                    <Avatar name={msg.senderName} size="sm" />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-slate-900">{msg.senderName}</span>
                        <span className="text-[11px] text-slate-400">{formatRelativeTime(msg.createdAt)}</span>
                      </div>
                      <p className="text-xs text-slate-600 mt-1 line-clamp-2 leading-relaxed">
                        {msg.content}
                      </p>
                      {msg.attachments && msg.attachments.length > 0 && (
                        <div className="mt-2 inline-flex items-center gap-2 px-2.5 py-1 bg-slate-50 border border-slate-200 rounded-lg text-xs font-medium text-slate-700">
                          <FileIcon type={msg.attachments[0].fileType} className="w-3.5 h-3.5" />
                          <span className="truncate max-w-xs">{msg.attachments[0].fileName}</span>
                          <span className="text-slate-400 font-mono text-[10px]">
                            ({formatBytes(msg.attachments[0].fileSize)})
                          </span>
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>

            {/* Recent Files */}
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between w-full">
                  <div className="flex items-center gap-2">
                    <FolderPlus className="w-4 h-4 text-indigo-600" />
                    <CardTitle>Recent Workspace Files</CardTitle>
                  </div>
                  <Link
                    href={`/w/${workspace.id}/files`}
                    className="text-xs text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1"
                  >
                    <span>Browse file manager</span>
                    <ChevronRight className="w-3.5 h-3.5" />
                  </Link>
                </div>
              </CardHeader>
              <CardContent className="p-0 divide-y divide-slate-100">
                {recentFiles.map((file) => (
                  <div
                    key={file.id}
                    className="p-3.5 px-4 hover:bg-slate-50/60 transition-colors flex items-center justify-between gap-3"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <div className="w-8 h-8 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                        <FileIcon type={file.type} className="w-4 h-4 text-indigo-600" />
                      </div>
                      <div className="min-w-0">
                        <div className="text-xs font-semibold text-slate-900 truncate" title={file.name}>
                          {file.name}
                        </div>
                        <div className="text-[11px] text-slate-400 flex items-center gap-2 mt-0.5">
                          <span>{formatBytes(file.size)}</span>
                          <span>•</span>
                          <span>By {file.ownerName}</span>
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center gap-2.5">
                      <Badge
                        variant={
                          file.availability === 'local'
                            ? 'success'
                            : file.availability === 'peer'
                            ? 'p2p'
                            : 'neutral'
                        }
                        size="sm"
                      >
                        {file.availability === 'local'
                          ? 'Available locally'
                          : file.availability === 'peer'
                          ? `From ${file.availableFromDevice || 'Peer'}`
                          : 'Offline'}
                      </Badge>
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={() => openSendModal({ name: file.name, size: file.size })}
                      >
                        Send P2P
                      </Button>
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>

          {/* Right Column: Direct P2P Transfers & Active Peers */}
          <div className="space-y-6">
            {/* Active P2P Transfers */}
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between w-full">
                  <div className="flex items-center gap-2">
                    <Zap className="w-4 h-4 text-teal-600" />
                    <CardTitle>P2P Direct Transfers</CardTitle>
                  </div>
                  <Link
                    href={`/w/${workspace.id}/transfers`}
                    className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
                  >
                    All
                  </Link>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                {activeTransfers.map((t) => (
                  <TransferProgressCard key={t.id} transfer={t} compact />
                ))}
              </CardContent>
            </Card>

            {/* Online Members / Sessions */}
            <Card>
              <CardHeader>
                <CardTitle>Team Members & Devices</CardTitle>
              </CardHeader>
              <CardContent className="p-0 divide-y divide-slate-100">
                {members.map((m) => (
                  <div key={m.id} className="p-3.5 px-4 flex items-center justify-between">
                    <div className="flex items-center gap-2.5 min-w-0">
                      <Avatar name={m.name} size="sm" status={m.status} />
                      <div className="min-w-0">
                        <div className="text-xs font-semibold text-slate-900 truncate">{m.name}</div>
                        <div className="text-[10px] text-slate-400 capitalize truncate">
                          {m.activeSessions[0]
                            ? `${m.activeSessions[0].browser.split(' ')[0]} on ${m.activeSessions[0].os.split(' ')[0]}`
                            : m.status}
                        </div>
                      </div>
                    </div>
                    <Badge variant={m.role === 'OWNER' ? 'default' : 'neutral'} size="sm">
                      {m.role}
                    </Badge>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>
        </div>
      </main>
    </div>
  );
}
