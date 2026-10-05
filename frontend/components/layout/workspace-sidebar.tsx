'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { cn } from '@/lib/utils';
import {
  Home,
  MessageSquare,
  FolderOpen,
  ArrowLeftRight,
  Users,
  Settings,
  Hash,
  Plus,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ShieldAlert,
  Server,
  Zap,
} from 'lucide-react';
import { Avatar } from '../ui/avatar';
import { StatusDot } from '../ui/badge';
import { useAuth } from '@/lib/auth/auth-context';
import { MOCK_WORKSPACES, MOCK_CHANNELS } from '@/lib/mock-data';

export function WorkspaceSidebar({ workspaceId }: { workspaceId: string }) {
  const pathname = usePathname();
  const router = useRouter();
  const { user, serverInfo } = useAuth();
  const [collapsed, setCollapsed] = useState(false);
  const [isWorkspaceMenuOpen, setIsWorkspaceMenuOpen] = useState(false);

  const currentWorkspace =
    MOCK_WORKSPACES.find((w) => w.id === workspaceId) || MOCK_WORKSPACES[0];
  const channels = MOCK_CHANNELS[workspaceId] || MOCK_CHANNELS.ws_dev || [];

  const navItems = [
    { label: 'Home', href: `/w/${workspaceId}`, icon: <Home className="w-4 h-4" /> },
    { label: 'Chat', href: `/w/${workspaceId}/chat`, icon: <MessageSquare className="w-4 h-4" /> },
    { label: 'Files', href: `/w/${workspaceId}/files`, icon: <FolderOpen className="w-4 h-4" /> },
    {
      label: 'Transfers',
      href: `/w/${workspaceId}/transfers`,
      icon: <ArrowLeftRight className="w-4 h-4" />,
    },
    { label: 'Members', href: `/w/${workspaceId}/members`, icon: <Users className="w-4 h-4" /> },
    { label: 'Settings', href: `/w/${workspaceId}/settings`, icon: <Settings className="w-4 h-4" /> },
  ];

  return (
    <aside
      className={cn(
        'relative flex flex-col bg-slate-900 text-slate-300 border-r border-slate-800 transition-all duration-200 select-none z-30',
        collapsed ? 'w-16' : 'w-64'
      )}
    >
      {/* Brand Header & Workspace Selector */}
      <div className="h-14 border-b border-slate-800 px-3 flex items-center justify-between">
        {!collapsed ? (
          <div className="relative w-full">
            <button
              type="button"
              onClick={() => setIsWorkspaceMenuOpen(!isWorkspaceMenuOpen)}
              className="w-full flex items-center justify-between p-1.5 rounded-lg hover:bg-slate-800/80 transition-colors text-left"
            >
              <div className="flex items-center gap-2.5 min-w-0">
                <div className="w-7 h-7 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-white text-xs shadow-sm flex-shrink-0">
                  {currentWorkspace.name.substring(0, 2).toUpperCase()}
                </div>
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-white truncate">
                    {currentWorkspace.name}
                  </div>
                  <div className="text-[10px] text-slate-400 capitalize">
                    {currentWorkspace.role.toLowerCase()}
                  </div>
                </div>
              </div>
              <ChevronDown className="w-3.5 h-3.5 text-slate-400 flex-shrink-0" />
            </button>

            {/* Workspace Dropdown */}
            {isWorkspaceMenuOpen && (
              <>
                <div
                  className="fixed inset-0 z-40"
                  onClick={() => setIsWorkspaceMenuOpen(false)}
                />
                <div className="absolute top-12 left-0 w-60 bg-slate-850 bg-slate-800 border border-slate-700 rounded-xl shadow-panel p-1.5 z-50 animate-in fade-in zoom-in-95 duration-150">
                  <div className="px-2 py-1.5 text-[10px] font-semibold text-slate-400 uppercase tracking-wider">
                    Workspaces
                  </div>
                  {MOCK_WORKSPACES.map((w) => (
                    <button
                      key={w.id}
                      onClick={() => {
                        setIsWorkspaceMenuOpen(false);
                        router.push(`/w/${w.id}`);
                      }}
                      className={cn(
                        'w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs transition-colors text-left',
                        w.id === workspaceId
                          ? 'bg-indigo-600/30 text-white font-medium'
                          : 'text-slate-300 hover:bg-slate-700/60'
                      )}
                    >
                      <span className="truncate">{w.name}</span>
                      <span className="text-[10px] text-slate-400">{w.membersCount}m</span>
                    </button>
                  ))}
                  <div className="my-1 border-t border-slate-700" />
                  <Link
                    href="/workspaces"
                    onClick={() => setIsWorkspaceMenuOpen(false)}
                    className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs text-indigo-400 hover:bg-slate-700/60 font-medium"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    <span>All Workspaces</span>
                  </Link>
                </div>
              </>
            )}
          </div>
        ) : (
          <div className="w-full flex justify-center">
            <div className="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center font-bold text-white text-xs">
              {currentWorkspace.name.substring(0, 2).toUpperCase()}
            </div>
          </div>
        )}
      </div>

      {/* Main Navigation */}
      <div className="flex-1 overflow-y-auto px-2 py-3 space-y-4">
        {/* Core items */}
        <div className="space-y-0.5">
          {navItems.map((item) => {
            const isActive =
              item.href === `/w/${workspaceId}`
                ? pathname === `/w/${workspaceId}`
                : pathname?.startsWith(item.href);

            return (
              <Link
                key={item.href}
                href={item.href}
                title={collapsed ? item.label : undefined}
                className={cn(
                  'flex items-center gap-3 px-2.5 py-2 rounded-lg text-xs font-medium transition-colors',
                  isActive
                    ? 'bg-indigo-600 text-white shadow-sm'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                )}
              >
                <span className="flex-shrink-0">{item.icon}</span>
                {!collapsed && <span>{item.label}</span>}
              </Link>
            );
          })}
        </div>

        {/* Channels Section (when not collapsed) */}
        {!collapsed && (
          <div className="pt-2">
            <div className="px-2.5 pb-1 flex items-center justify-between text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
              <span>Channels</span>
              <button
                type="button"
                className="hover:text-slate-200 p-0.5"
                title="Create Channel"
              >
                <Plus className="w-3.5 h-3.5" />
              </button>
            </div>
            <div className="space-y-0.5 mt-1">
              {channels.map((ch) => {
                const isChannelActive = pathname === `/w/${workspaceId}/chat/${ch.id}`;
                return (
                  <Link
                    key={ch.id}
                    href={`/w/${workspaceId}/chat/${ch.id}`}
                    className={cn(
                      'flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs transition-colors',
                      isChannelActive
                        ? 'bg-slate-800 text-white font-medium'
                        : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/40'
                    )}
                  >
                    <Hash className="w-3.5 h-3.5 text-slate-500" />
                    <span className="truncate">{ch.name}</span>
                  </Link>
                );
              })}
            </div>
          </div>
        )}

        {/* Admin Link if authorized */}
        {user?.role === 'SYSTEM_ADMIN' && (
          <div className="pt-2 border-t border-slate-800">
            <Link
              href="/admin"
              title={collapsed ? 'Server Control Center' : undefined}
              className={cn(
                'flex items-center gap-3 px-2.5 py-2 rounded-lg text-xs font-medium text-amber-400 hover:bg-amber-400/10 transition-colors'
              )}
            >
              <ShieldAlert className="w-4 h-4 flex-shrink-0" />
              {!collapsed && <span>Server Admin</span>}
            </Link>
          </div>
        )}
      </div>

      {/* Footer Area: Connection & Current User */}
      <div className="border-t border-slate-800 p-2.5 bg-slate-950/40">
        {!collapsed ? (
          <div className="space-y-2">
            {/* Server Connection Status */}
            <div className="flex items-center justify-between px-1 text-[11px] text-slate-400">
              <span className="flex items-center gap-1.5 truncate max-w-[140px]">
                <StatusDot status={serverInfo.status === 'connected' ? 'connected' : 'offline'} />
                <span className="truncate">{serverInfo.serverName}</span>
              </span>
              <span className="inline-flex items-center gap-0.5 text-teal-400 text-[10px] font-mono">
                <Zap className="w-2.5 h-2.5" />
                P2P
              </span>
            </div>

            {/* User Profile */}
            <div className="flex items-center justify-between p-1.5 rounded-lg bg-slate-850 bg-slate-800/50">
              <div className="flex items-center gap-2 min-w-0">
                <Avatar name={user?.name || 'User'} size="sm" status={user?.status || 'online'} />
                <div className="min-w-0">
                  <div className="text-xs font-medium text-white truncate">{user?.name}</div>
                  <div className="text-[10px] text-slate-400 truncate">{user?.email}</div>
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-2">
            <StatusDot status={serverInfo.status === 'connected' ? 'connected' : 'offline'} />
            <Avatar name={user?.name || 'User'} size="sm" />
          </div>
        )}

        {/* Collapse toggle button */}
        <button
          type="button"
          onClick={() => setCollapsed(!collapsed)}
          className="w-full mt-2 py-1 flex items-center justify-center text-slate-500 hover:text-slate-300 hover:bg-slate-800/60 rounded text-xs transition-colors"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
        </button>
      </div>
    </aside>
  );
}
