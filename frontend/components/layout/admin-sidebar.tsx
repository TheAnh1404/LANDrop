'use client';

import React from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils';
import {
  Activity,
  Users,
  Monitor,
  KeyRound,
  Layers,
  ArrowLeftRight,
  Server,
  FileText,
  ArrowLeft,
  ShieldCheck,
} from 'lucide-react';

export function AdminSidebar() {
  const pathname = usePathname();

  const navItems = [
    { label: 'Overview', href: '/admin', icon: <Activity className="w-4 h-4" /> },
    { label: 'Users', href: '/admin/users', icon: <Users className="w-4 h-4" /> },
    { label: 'Clients', href: '/admin/clients', icon: <Monitor className="w-4 h-4" /> },
    { label: 'Sessions', href: '/admin/sessions', icon: <KeyRound className="w-4 h-4" /> },
    { label: 'Workspaces', href: '/admin/workspaces', icon: <Layers className="w-4 h-4" /> },
    { label: 'Transfers', href: '/admin/transfers', icon: <ArrowLeftRight className="w-4 h-4" /> },
    { label: 'Server', href: '/admin/server', icon: <Server className="w-4 h-4" /> },
    { label: 'Logs', href: '/admin/logs', icon: <FileText className="w-4 h-4" /> },
  ];

  return (
    <aside className="w-64 bg-slate-900 text-slate-300 border-r border-slate-800 flex flex-col select-none z-30 flex-shrink-0">
      {/* Brand Header */}
      <div className="h-14 border-b border-slate-800 px-4 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-amber-500/20 border border-amber-500/40 flex items-center justify-center text-amber-400">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <div>
            <div className="text-xs font-semibold text-white tracking-wide">LANDrop Server</div>
            <div className="text-[10px] text-amber-400 font-medium">Control Center</div>
          </div>
        </div>
      </div>

      {/* Nav List */}
      <div className="flex-1 overflow-y-auto p-3 space-y-1">
        <div className="px-2 py-1 text-[10px] font-semibold text-slate-400 uppercase tracking-wider">
          Operations
        </div>
        {navItems.map((item) => {
          const isActive =
            item.href === '/admin' ? pathname === '/admin' : pathname?.startsWith(item.href);

          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                'flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-medium transition-colors',
                isActive
                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30 font-semibold'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              )}
            >
              <span>{item.icon}</span>
              <span>{item.label}</span>
            </Link>
          );
        })}
      </div>

      {/* Back to Workspace Footer */}
      <div className="p-3 border-t border-slate-800 bg-slate-950/40">
        <Link
          href="/w/ws_dev"
          className="flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs text-slate-400 hover:text-white hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft className="w-4 h-4" />
          <span>Back to Workspace</span>
        </Link>
      </div>
    </aside>
  );
}
