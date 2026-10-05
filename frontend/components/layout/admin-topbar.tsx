'use client';

import React from 'react';
import { useAuth } from '@/lib/auth/auth-context';
import { Badge } from '../ui/badge';
import { Avatar } from '../ui/avatar';
import { Activity, Server, ArrowLeft } from 'lucide-react';
import Link from 'next/link';

export function AdminTopBar({
  title,
  subtitle,
  actions,
}: {
  title?: string;
  subtitle?: string;
  actions?: React.ReactNode;
}) {
  const { user } = useAuth();

  return (
    <header className="h-14 bg-white border-b border-slate-200 px-6 flex items-center justify-between gap-4 select-none z-20 flex-shrink-0">
      <div>
        <h1 className="text-sm font-semibold text-slate-900 truncate">
          {title || 'Server Control Center'}
        </h1>
        {subtitle && <p className="text-[11px] text-slate-500 truncate">{subtitle}</p>}
      </div>

      <div className="flex items-center gap-3">
        {actions}

        <Badge variant="success" size="sm" className="hidden sm:inline-flex">
          <Activity className="w-3 h-3 text-emerald-600" />
          Server Healthy • Port 3000
        </Badge>

        <Link
          href="/w/ws_dev"
          className="text-xs text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1 sm:hidden"
        >
          <ArrowLeft className="w-3 h-3" />
          Workspace
        </Link>

        <div className="flex items-center gap-2 pl-2 border-l border-slate-200">
          <Avatar name={user?.name || 'Admin'} size="sm" />
          <span className="text-xs font-semibold text-slate-700 hidden md:inline">
            {user?.name}
          </span>
        </div>
      </div>
    </header>
  );
}
