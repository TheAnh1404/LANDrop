'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { Activity, ArrowDownUp, ArrowLeft, ChevronRight, CircleHelp, FileText, FolderOpen, Hash, LayoutDashboard, LogOut, Menu, Monitor, Network, Plus, Server, Settings2, ShieldCheck, Users, X } from 'lucide-react';
import { useSession } from '@/lib/session/session-provider';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useWorkspaces } from '@/lib/data/use-workspaces';
import { Initials, Tag } from './primitives';
import { cn } from '@/lib/utils';

export function AuthGate({ children, admin = false }: { children: React.ReactNode; admin?: boolean }) {
  const { ready, user, isAdmin } = useSession();
  const router = useRouter();
  useEffect(() => { if (ready && !user) router.replace(admin ? '/auth/login?admin=1' : '/auth/login'); }, [ready, user, router, admin]);
  if (!ready || !user) return <div className="boot-state"><span className="brand-mark">L</span><p>Đang khôi phục phiên làm việc…</p></div>;
  if (admin && !isAdmin) return <div className="boot-state"><ShieldCheck size={36} /><h1>Khu vực quản trị</h1><p>Tài khoản hiện tại không có quyền quản trị hệ thống.</p><Link className="action action-secondary" href="/workspaces">Về không gian làm việc</Link></div>;
  return <>{children}</>;
}

type Item = { label: string; href: string; icon: React.ReactNode };
function Shell({ children, title, items, workspaceName, channels, workspaceId, admin = false }: { children: React.ReactNode; title: string; items: Item[]; workspaceName?: string; channels?: { id: string; name: string }[]; workspaceId?: string; admin?: boolean }) {
  const { user, mode, serverInfo, logout, isAdmin } = useSession();
  const pathname = usePathname();
  const router = useRouter();
  const [mobile, setMobile] = useState(false);
  const [help, setHelp] = useState(false);
  useEffect(() => { setMobile(false); }, [pathname]);
  const active = (href: string) => pathname === href || (href.endsWith('/chat') && pathname.startsWith(`${href}/`));
  const current = items.find(item => active(item.href))?.label || title;
  return <div className="app-shell">
    {mobile && <button className="sidebar-overlay" aria-label="Đóng điều hướng" onClick={() => setMobile(false)} />}
    <aside className={cn('app-sidebar', mobile && 'sidebar-open')}>
      <Link href="/workspaces" className="brand"><span className="brand-mark">L</span><div><strong>LANDrop<span className="brand-period">.</span></strong><small>{admin ? 'Trung tâm quản trị' : 'Không gian cộng tác'}</small></div></Link>
      <div className="sidebar-context"><span className="context-icon">{admin ? <ShieldCheck size={19} /> : <FolderOpen size={19} />}</span><div><strong>{workspaceName || (admin ? 'Quản trị hệ thống' : 'Các không gian')}</strong><small>{admin ? 'SYSTEM ADMIN' : 'WORKSPACE'}</small></div>{workspaceId && <Link href="/workspaces" aria-label="Đổi không gian"><ChevronRight size={16} /></Link>}</div>
      <div className="nav-caption">{admin ? 'VẬN HÀNH' : 'KHÔNG GIAN LÀM VIỆC'}</div>
      <nav aria-label="Điều hướng chính">{items.map(item => <Link key={item.href} href={item.href} className={cn('nav-link', active(item.href) && 'nav-active')} aria-current={active(item.href) ? 'page' : undefined}>{item.icon}<span>{item.label}</span>{active(item.href) && <span className="nav-indicator" />}</Link>)}</nav>
      {!!channels?.length && <div className="sidebar-channels"><div className="nav-caption">KÊNH THẢO LUẬN</div>{channels.slice(0, 6).map(channel => <Link className={cn('channel-link', pathname.endsWith(`/${channel.id}`) && 'channel-active')} key={channel.id} href={`/w/${workspaceId}/chat/${channel.id}`}><Hash size={15} /><span>{channel.name}</span></Link>)}</div>}
      <div className="sidebar-bottom">
        {isAdmin && !admin && <Link href="/admin" className="nav-link"><ShieldCheck size={17} /><span>Quản trị hệ thống</span><ChevronRight size={14} /></Link>}
        {admin && <Link href="/workspaces" className="nav-link"><ArrowLeft size={17} /><span>Về workspace</span></Link>}
        <button className="nav-link" onClick={() => setHelp(!help)} aria-expanded={help}><CircleHelp size={17} /><span>Hướng dẫn sử dụng</span></button>
        {help && <p className="sidebar-help">Chọn không gian → trao đổi trong kênh → quản lý tài liệu. File LAN cần thiết bị nguồn khả dụng; trình duyệt hiện chưa có cầu nối TCP.</p>}
        <div className="sidebar-server"><span className={`status-light ${mode === 'demo' ? 'status-demo' : serverInfo.status !== 'connected' ? 'status-offline' : ''}`} /><div><strong>{mode === 'demo' ? 'Phiên trải nghiệm' : 'LANDrop Server'}</strong><small>{mode === 'demo' ? 'Dữ liệu mẫu' : serverInfo.serverUrl.replace(/^https?:\/\//, '')}</small></div><Link href="/connect" aria-label="Thiết lập kết nối"><Settings2 size={15} /></Link></div>
      </div>
    </aside>
    <div className="app-main">
      <header className="app-header"><div className="header-left"><button className="mobile-menu" aria-label="Mở điều hướng" onClick={() => setMobile(true)}><Menu size={21} /></button><Link className="breadcrumb-root" href="/workspaces">{admin ? 'Quản trị' : 'Không gian'}</Link><ChevronRight size={14} className="muted" /><span>{current}</span></div><div className="header-right"><Link href="/connect" className="connection-pill"><span className={`status-light ${mode === 'demo' ? 'status-demo' : serverInfo.status !== 'connected' ? 'status-offline' : ''}`} />{mode === 'demo' ? 'Bản demo' : serverInfo.status === 'connected' ? 'Đã kết nối' : 'Chưa kết nối'}</Link><span className="header-divider" /><Initials name={user?.name || 'User'} /><div className="header-user"><strong>{user?.name}</strong><small>{admin ? 'Quản trị viên' : 'Thành viên'}</small></div><button aria-label="Đăng xuất" title="Đăng xuất" onClick={async () => { await logout(); router.push('/connect'); }}><LogOut size={17} /></button></div></header>
      {mode === 'demo' && <div className="demo-banner"><span>DỮ LIỆU MẪU</span> Bạn đang trải nghiệm giao diện. Thao tác tại đây không thay đổi dữ liệu trên server.<Link href="/connect">Kết nối server thật <ChevronRight size={13} /></Link></div>}
      <main id="main-content" className="page-container">{children}</main>
    </div>
  </div>;
}

export function WorkspaceShell({ children, id }: { children: React.ReactNode; id: string }) {
  const { workspace, channels } = useWorkspace();
  const base = `/w/${id}`;
  return <Shell title="Tổng quan" workspaceId={id} workspaceName={workspace?.name || 'Đang tải workspace…'} channels={channels} items={[
    { label: 'Tổng quan', href: base, icon: <LayoutDashboard size={18} /> },
    { label: 'Thảo luận', href: `${base}/chat`, icon: <Hash size={18} /> },
    { label: 'Tài liệu', href: `${base}/files`, icon: <FolderOpen size={18} /> },
    { label: 'Truyền tệp', href: `${base}/transfers`, icon: <ArrowDownUp size={18} /> },
    { label: 'Thành viên', href: `${base}/members`, icon: <Users size={18} /> },
    { label: 'Cài đặt', href: `${base}/settings`, icon: <Settings2 size={18} /> }
  ]}>{children}</Shell>;
}
export function WorkspacesShell({ children }: { children: React.ReactNode }) { return <Shell title="Các không gian" items={[{ label: 'Các không gian', href: '/workspaces', icon: <LayoutDashboard size={18} /> }, { label: 'Kết nối server', href: '/connect', icon: <Network size={18} /> }]}>{children}</Shell>; }
export function AdminShell({ children }: { children: React.ReactNode }) { return <Shell title="Tổng quan" admin items={[
  { label: 'Tổng quan', href: '/admin', icon: <LayoutDashboard size={18} /> }, { label: 'Người dùng', href: '/admin/users', icon: <Users size={18} /> },
  { label: 'Không gian', href: '/admin/workspaces', icon: <FolderOpen size={18} /> }, { label: 'Thiết bị kết nối', href: '/admin/clients', icon: <Monitor size={18} /> },
  { label: 'Phiên đăng nhập', href: '/admin/sessions', icon: <ShieldCheck size={18} /> }, { label: 'Truyền tệp', href: '/admin/transfers', icon: <ArrowDownUp size={18} /> },
  { label: 'Server & mạng', href: '/admin/server', icon: <Server size={18} /> }, { label: 'Nhật ký', href: '/admin/logs', icon: <Activity size={18} /> }
]}>{children}</Shell>; }
