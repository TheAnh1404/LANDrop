import React from 'react';
import { cn } from '@/lib/utils';
import { StatusDot } from './badge';
import { UserStatus } from '@/lib/types';

export interface AvatarProps extends React.HTMLAttributes<HTMLDivElement> {
  name: string;
  src?: string;
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl';
  status?: UserStatus;
}

export function Avatar({ name, src, size = 'md', status, className, ...props }: AvatarProps) {
  const initials = name
    .split(' ')
    .map((n) => n[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  // Consistent color hash based on name
  const bgColors = [
    'bg-indigo-600',
    'bg-blue-600',
    'bg-teal-600',
    'bg-emerald-600',
    'bg-rose-600',
    'bg-purple-600',
    'bg-slate-700',
  ];
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  const colorIndex = Math.abs(hash) % bgColors.length;
  const bgColor = bgColors[colorIndex];

  const sizeClasses = {
    xs: 'w-5 h-5 text-[10px]',
    sm: 'w-7 h-7 text-xs',
    md: 'w-9 h-9 text-sm',
    lg: 'w-11 h-11 text-base',
    xl: 'w-14 h-14 text-lg font-semibold',
  };

  return (
    <div className={cn('relative inline-flex flex-shrink-0 select-none', className)} {...props}>
      {src ? (
        <img
          src={src}
          alt={name}
          className={cn('rounded-full object-cover border border-slate-200', sizeClasses[size])}
        />
      ) : (
        <div
          className={cn(
            'rounded-full flex items-center justify-center font-medium text-white shadow-subtle',
            bgColor,
            sizeClasses[size]
          )}
        >
          {initials || '?'}
        </div>
      )}
      {status && (
        <span className="absolute bottom-0 right-0 transform translate-x-0.5 translate-y-0.5">
          <StatusDot status={status} />
        </span>
      )}
    </div>
  );
}

export function AvatarGroup({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex -space-x-2 overflow-hidden items-center', className)}>
      {children}
    </div>
  );
}
