'use client';

import React, { useState } from 'react';
import { useAuth } from '@/lib/auth/auth-context';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { ConnectionStatusBadge } from '../ui/connection-status';
import { Button, IconButton } from '../ui/button';
import { SearchInput } from '../ui/input';
import { Avatar } from '../ui/avatar';
import { Send, Download, Settings, Bell, ArrowUpRight } from 'lucide-react';
import { UserSettingsModal } from './user-settings-modal';

export function WorkspaceTopBar({
  title,
  subtitle,
  actions,
}: {
  title?: string;
  subtitle?: string;
  actions?: React.ReactNode;
}) {
  const { user, serverInfo } = useAuth();
  const { openSendModal, triggerSimulatedIncoming } = useTransfer();
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  return (
    <>
      <header className="h-14 bg-white border-b border-slate-200/80 px-6 flex items-center justify-between gap-4 select-none z-20 flex-shrink-0">
        {/* Left: Page Title / Subtitle */}
        <div className="flex items-center gap-3 min-w-0">
          <div>
            <h1 className="text-sm font-semibold text-slate-900 truncate">
              {title || 'Workspace'}
            </h1>
            {subtitle && <p className="text-[11px] text-slate-500 truncate">{subtitle}</p>}
          </div>
        </div>

        {/* Center: Search */}
        <div className="hidden md:flex max-w-sm w-full">
          <SearchInput placeholder="Search messages, files, members..." />
        </div>

        {/* Right: Actions, Send File, Status & User */}
        <div className="flex items-center gap-2.5">
          {actions}

          {/* Prototype Simulation Action */}
          <Button
            variant="ghost"
            size="xs"
            onClick={triggerSimulatedIncoming}
            title="Simulate an incoming P2P transfer from peer Ken"
            className="text-slate-500 hover:text-indigo-600 hidden lg:inline-flex"
          >
            <Download className="w-3.5 h-3.5 text-teal-600" />
            <span>Simulate Incoming</span>
          </Button>

          {/* Signature Send File Button */}
          <Button
            variant="primary"
            size="sm"
            onClick={() => openSendModal()}
            leftIcon={<Send className="w-3.5 h-3.5" />}
          >
            Send File
          </Button>

          {/* Connection Status Badge */}
          <ConnectionStatusBadge serverInfo={serverInfo} className="hidden sm:inline-flex" />

          {/* Settings Trigger */}
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="User Settings"
            onClick={() => setIsSettingsOpen(true)}
            title="User and Session Settings"
          >
            <Settings className="w-4 h-4" />
          </IconButton>

          {/* User Avatar */}
          <button
            type="button"
            onClick={() => setIsSettingsOpen(true)}
            className="focus-ring rounded-full"
            title="Account Settings"
          >
            <Avatar name={user?.name || 'User'} size="sm" status={user?.status || 'online'} />
          </button>
        </div>
      </header>

      {/* User Settings Modal */}
      <UserSettingsModal isOpen={isSettingsOpen} onClose={() => setIsSettingsOpen(false)} />
    </>
  );
}
