import React from 'react';
import { cn } from '@/lib/utils';
import {
  FileText,
  FileCode,
  FileArchive,
  Image as ImageIcon,
  Video,
  Music,
  Folder,
  File as FileGeneric,
  Database,
  Table as TableIcon,
} from 'lucide-react';

export function FileIcon({
  type,
  className,
  isFolder = false,
}: {
  type?: string;
  className?: string;
  isFolder?: boolean;
}) {
  if (isFolder) {
    return <Folder className={cn('w-4 h-4 text-amber-500 fill-amber-500/20', className)} />;
  }

  const cleanType = (type || '').toLowerCase().replace(/^\./, '');

  switch (cleanType) {
    case 'zip':
    case 'tar':
    case 'gz':
    case 'tar.gz':
    case 'rar':
    case '7z':
      return <FileArchive className={cn('w-4 h-4 text-purple-600', className)} />;
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'svg':
    case 'gif':
    case 'webp':
    case 'fig':
      return <ImageIcon className={cn('w-4 h-4 text-rose-500', className)} />;
    case 'mp4':
    case 'mov':
    case 'mkv':
    case 'webm':
      return <Video className={cn('w-4 h-4 text-blue-500', className)} />;
    case 'mp3':
    case 'wav':
    case 'flac':
      return <Music className={cn('w-4 h-4 text-emerald-500', className)} />;
    case 'sql':
    case 'db':
    case 'sqlite':
      return <Database className={cn('w-4 h-4 text-indigo-500', className)} />;
    case 'csv':
    case 'xlsx':
    case 'json':
      return <TableIcon className={cn('w-4 h-4 text-teal-600', className)} />;
    case 'js':
    case 'ts':
    case 'tsx':
    case 'jsx':
    case 'py':
    case 'java':
    case 'go':
    case 'rs':
    case 'html':
    case 'css':
      return <FileCode className={cn('w-4 h-4 text-amber-600', className)} />;
    case 'pdf':
    case 'doc':
    case 'docx':
    case 'txt':
    case 'md':
      return <FileText className={cn('w-4 h-4 text-slate-600', className)} />;
    default:
      return <FileGeneric className={cn('w-4 h-4 text-slate-500', className)} />;
  }
}

export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
}: {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center text-center p-8 rounded-xl border border-dashed border-slate-200 bg-slate-50/50',
        className
      )}
    >
      {icon && (
        <div className="w-12 h-12 rounded-full bg-white border border-slate-200/80 shadow-subtle flex items-center justify-center text-slate-400 mb-3">
          {icon}
        </div>
      )}
      <h3 className="text-sm font-semibold text-slate-800">{title}</h3>
      {description && (
        <p className="text-xs text-slate-500 mt-1 max-w-sm leading-relaxed">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function Breadcrumb({
  items,
  onNavigate,
  className,
}: {
  items: { label: string; id?: string | null }[];
  onNavigate?: (id: string | null) => void;
  className?: string;
}) {
  return (
    <nav className={cn('flex items-center gap-1.5 text-xs text-slate-500 select-none', className)}>
      {items.map((item, index) => {
        const isLast = index === items.length - 1;
        return (
          <React.Fragment key={index}>
            {index > 0 && <span className="text-slate-300">/</span>}
            {isLast ? (
              <span className="font-semibold text-slate-900">{item.label}</span>
            ) : (
              <button
                type="button"
                onClick={() => onNavigate?.(item.id || null)}
                className="hover:text-indigo-600 transition-colors"
              >
                {item.label}
              </button>
            )}
          </React.Fragment>
        );
      })}
    </nav>
  );
}

export function Tabs({
  tabs,
  activeTab,
  onChange,
  className,
}: {
  tabs: { id: string; label: string; count?: number; icon?: React.ReactNode }[];
  activeTab: string;
  onChange: (id: string) => void;
  className?: string;
}) {
  return (
    <div className={cn('flex items-center border-b border-slate-200 gap-1', className)}>
      {tabs.map((tab) => {
        const isActive = tab.id === activeTab;
        return (
          <button
            key={tab.id}
            type="button"
            onClick={() => onChange(tab.id)}
            className={cn(
              'flex items-center gap-2 px-3.5 py-2 text-xs font-medium border-b-2 -mb-px transition-colors',
              isActive
                ? 'border-indigo-600 text-indigo-600 font-semibold'
                : 'border-transparent text-slate-500 hover:text-slate-800 hover:border-slate-300'
            )}
          >
            {tab.icon}
            <span>{tab.label}</span>
            {tab.count !== undefined && (
              <span
                className={cn(
                  'text-[10px] px-1.5 py-0.5 rounded-full font-semibold',
                  isActive ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-100 text-slate-600'
                )}
              >
                {tab.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
