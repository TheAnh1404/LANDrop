'use client';

import React from 'react';
import { AlertCircle, ArrowUpRight, Check, File, Loader2, RefreshCw, Search, X } from 'lucide-react';
import { cn, formatBytes } from '@/lib/utils';

export function Action({ children, tone = 'primary', busy, disabled, className, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { tone?: 'primary' | 'secondary' | 'ghost' | 'danger'; busy?: boolean }) {
  return <button type="button" className={cn('action', `action-${tone}`, className)} disabled={busy || disabled} {...props}>{busy && <Loader2 size={16} className="animate-spin" />}{children}</button>;
}
export function Tag({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: 'neutral' | 'green' | 'amber' | 'blue' | 'red' }) { return <span className={`tag tag-${tone}`}>{children}</span>; }
export function Initials({ name, size = 'normal' }: { name: string; size?: 'normal' | 'large' }) {
  return <span className={`initials initials-${size}`}>{name.split(' ').filter(Boolean).slice(0, 2).map(part => part[0]).join('').toUpperCase() || 'L'}</span>;
}
export function Heading({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description?: string; action?: React.ReactNode }) {
  return <div className="page-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1>{description && <p>{description}</p>}</div><div className="heading-action">{action}</div></div>;
}
export function Panel({ title, description, action, children, className }: { title?: string; description?: string; action?: React.ReactNode; children: React.ReactNode; className?: string }) {
  return <section className={cn('panel', className)}>{title && <header className="panel-header"><div><h2>{title}</h2>{description && <p>{description}</p>}</div>{action}</header>}{children}</section>;
}
export function Stat({ label, value, detail, icon }: { label: string; value: React.ReactNode; detail?: string; icon?: React.ReactNode }) {
  return <div className="stat-card"><div className="stat-label">{label}<span>{icon}</span></div><strong>{value}</strong>{detail && <p>{detail}</p>}</div>;
}
export function SearchBox({ value, onChange, placeholder = 'Tìm kiếm…' }: { value: string; onChange: (value: string) => void; placeholder?: string }) {
  return <label className="search-box"><Search size={17} /><span className="sr-only">{placeholder}</span><input value={value} onChange={event => onChange(event.target.value)} placeholder={placeholder} />{value && <button type="button" aria-label="Xóa tìm kiếm" onClick={() => onChange('')}><X size={14} /></button>}</label>;
}
export function Field({ label, hint, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string }) {
  const id = React.useId();
  return <label className="field" htmlFor={id}><span>{label}</span><input id={id} {...props} />{hint && <small>{hint}</small>}</label>;
}
export function Notice({ children, danger = false }: { children: React.ReactNode; danger?: boolean }) {
  return <div className={`notice ${danger ? 'notice-danger' : ''}`} role={danger ? 'alert' : 'status'}><AlertCircle size={17} /><div>{children}</div></div>;
}
export function Feedback({ loading, error, retry }: { loading?: boolean; error?: string; retry?: () => void }) {
  if (loading) return <div className="loading-state" role="status"><Loader2 size={21} className="animate-spin" />Đang tải dữ liệu…</div>;
  if (error) return <Notice danger><div className="feedback-line"><span>{error}</span>{retry && <Action tone="secondary" onClick={retry}><RefreshCw size={14} />Thử lại</Action>}</div></Notice>;
  return null;
}
export function Empty({ title, description, action, icon }: { title: string; description?: string; action?: React.ReactNode; icon?: React.ReactNode }) {
  return <div className="empty-state"><div className="empty-icon">{icon || <File size={24} />}</div><h3>{title}</h3>{description && <p>{description}</p>}{action}</div>;
}
export function FileGlyph({ name }: { name: string }) {
  const extension = name.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE';
  return <span className="file-glyph"><File size={21} /><small>{extension}</small></span>;
}
export function FileRow({ name, size, detail, action }: { name: string; size: number; detail?: string; action?: React.ReactNode }) {
  return <div className="list-row"><FileGlyph name={name} /><div className="row-content"><strong>{name}</strong><span>{formatBytes(size)}{detail ? ` · ${detail}` : ''}</span></div>{action || <ArrowUpRight size={16} className="muted" />}</div>;
}
export function Dialog({ title, description, children, onClose }: { title: string; description?: string; children: React.ReactNode; onClose: () => void }) {
  const label = React.useId();
  const root = React.useRef<HTMLDivElement>(null);
  const close = React.useRef(onClose);
  close.current = onClose;
  React.useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const selector = 'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]';
    const elements = () => Array.from(root.current?.querySelectorAll<HTMLElement>(selector) || []);
    elements()[0]?.focus();
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close.current();
      if (event.key === 'Tab') {
        const items = elements();
        const first = items[0], last = items[items.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    };
    document.addEventListener('keydown', key);
    return () => { document.body.style.overflow = oldOverflow; document.removeEventListener('keydown', key); previous?.focus(); };
  }, []);
  return <div className="dialog-backdrop" onClick={onClose}><div className="dialog" ref={root} role="dialog" aria-modal="true" aria-labelledby={label} onClick={event => event.stopPropagation()}><header><div><h2 id={label}>{title}</h2>{description && <p>{description}</p>}</div><button type="button" aria-label="Đóng" onClick={onClose}><X size={20} /></button></header>{children}</div></div>;
}
export function Success({ children }: { children: React.ReactNode }) { return <p className="success" role="status"><Check size={16} />{children}</p>; }
