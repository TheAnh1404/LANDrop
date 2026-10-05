import React from 'react';
import { cn } from '@/lib/utils';
import { UserStatus } from '@/lib/types';

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  variant?: 'default' | 'neutral' | 'success' | 'warning' | 'danger' | 'p2p';
  size?: 'sm' | 'md';
}

export function Badge({
  className,
  variant = 'default',
  size = 'md',
  children,
  ...props
}: BadgeProps) {
  const variants = {
    default: 'bg-indigo-50 text-indigo-700 border-indigo-200/60',
    neutral: 'bg-slate-100 text-slate-700 border-slate-200',
    success: 'bg-emerald-50 text-emerald-700 border-emerald-200/60',
    warning: 'bg-amber-50 text-amber-700 border-amber-200/60',
    danger: 'bg-red-50 text-red-700 border-red-200/60',
    p2p: 'bg-teal-50 text-teal-800 border-teal-200/80 font-medium',
  };

  const sizes = {
    sm: 'text-[11px] px-1.5 py-0.5 leading-none rounded',
    md: 'text-xs px-2 py-0.5 rounded-md font-medium',
  };

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 border transition-colors select-none',
        variants[variant],
        sizes[size],
        className
      )}
      {...props}
    >
      {children}
    </span>
  );
}

export interface StatusDotProps {
  status: UserStatus | 'connected' | 'connecting' | 'disconnected' | 'p2p';
  className?: string;
  showLabel?: boolean;
}

export function StatusDot({ status, className, showLabel = false }: StatusDotProps) {
  const statusColors = {
    online: 'bg-emerald-500 ring-emerald-100',
    connected: 'bg-emerald-500 ring-emerald-100',
    p2p: 'bg-teal-500 ring-teal-100',
    busy: 'bg-red-500 ring-red-100',
    away: 'bg-amber-500 ring-amber-100',
    connecting: 'bg-amber-500 ring-amber-100 animate-pulse',
    offline: 'bg-slate-400 ring-slate-100',
    disconnected: 'bg-slate-400 ring-slate-100',
  };

  const labels = {
    online: 'Online',
    connected: 'Connected',
    p2p: 'P2P Ready',
    busy: 'Busy',
    away: 'Away',
    connecting: 'Connecting...',
    offline: 'Offline',
    disconnected: 'Disconnected',
  };

  return (
    <div className={cn('inline-flex items-center gap-1.5', className)}>
      <span
        className={cn(
          'w-2 h-2 rounded-full ring-2 transition-colors',
          statusColors[status] || statusColors.offline
        )}
      />
      {showLabel && (
        <span className="text-xs text-slate-600 font-medium capitalize">
          {labels[status] || status}
        </span>
      )}
    </div>
  );
}
