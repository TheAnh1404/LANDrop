'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ArrowRight, FolderOpen, Hash, Network, Server, ShieldCheck } from 'lucide-react';
import { useSession } from '@/lib/session/session-provider';
import { Action, Field, Notice } from '../system/primitives';

function AccessFrame({ children }: { children: React.ReactNode }) {
  return <div className="auth-page"><section className="auth-story"><Link href="/connect" className="brand"><span className="brand-mark">L</span><div><strong>LANDrop<span className="brand-period">.</span></strong><small>Không gian cộng tác trong mạng LAN</small></div></Link><div className="auth-story-copy"><p className="eyebrow">KẾT NỐI. CỘNG TÁC. CHIA SẺ.</p><h1>Công việc liền mạch.<br />Dữ liệu trong tầm tay.</h1><p>Một nơi cho trao đổi của nhóm, tài liệu chung và hoạt động truyền tệp giữa các thiết bị.</p><div className="hero-diagram"><span className="hero-node"><Hash size={25} /></span><span className="hero-line" /><span className="hero-node hero-center"><Network size={34} /></span><span className="hero-line" /><span className="hero-node"><FolderOpen size={25} /></span></div></div><div className="auth-story-footer"><span><ShieldCheck size={14} />Phân quyền theo workspace</span><span><Network size={14} />Kết nối mạng nội bộ</span></div></section><section className="auth-form-side">{children}</section></div>;
}

export function ConnectScreen() {
  const { serverInfo, connectServer, enterDemo, error, ready } = useSession();
  const [url, setUrl] = useState(serverInfo.serverUrl);
  const [busy, setBusy] = useState(false);
  const router = useRouter();
  useEffect(() => { if (ready) setUrl(serverInfo.serverUrl); }, [ready, serverInfo.serverUrl]);
  return <AccessFrame><div className="auth-form"><p className="eyebrow">BẮT ĐẦU LÀM VIỆC</p><h2>Kết nối với nhóm của bạn</h2><p>Nhập địa chỉ backend LANDrop. Khi kết nối thành công, bạn có thể đăng nhập vào không gian làm việc.</p>{error && <Notice danger>{error}</Notice>}<form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); try { if (await connectServer(url)) router.push('/auth/login'); } finally { setBusy(false); } }}><Field label="Địa chỉ server" placeholder="http://localhost:3100" value={url} onChange={event => setUrl(event.target.value)} required autoComplete="url" hint="Chạy frontend và backend trên cùng máy: frontend 3000, backend 3100." /><Action type="submit" busy={busy} disabled={!ready}>Kết nối server <ArrowRight size={15} /></Action></form><div className="auth-divider">hoặc xem giao diện trước</div><Action tone="secondary" className="w-full" disabled={!ready} onClick={() => { enterDemo(); router.push('/workspaces'); }}>Khám phá bản demo</Action><p className="auth-form-footer">Demo sử dụng dữ liệu mẫu và không kết nối database.</p></div></AccessFrame>;
}

export function LoginScreen() {
  const { serverInfo, login, error } = useSession();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [admin, setAdmin] = useState(false);
  const [busy, setBusy] = useState(false);
  const router = useRouter();
  useEffect(() => { setAdmin(new URLSearchParams(window.location.search).get('admin') === '1'); }, []);
  return <AccessFrame><div className="auth-form"><p className="eyebrow">CHÀO MỪNG TRỞ LẠI</p><h2>{admin ? 'Đăng nhập quản trị' : 'Vào không gian của bạn'}</h2><p>{admin ? 'Sử dụng tài khoản được cấp quyền SYSTEM_ADMIN.' : 'Đăng nhập để xem workspace và tiếp tục công việc cùng nhóm.'}</p><div className="auth-server-label"><Server size={14} /><span>{serverInfo.serverUrl.replace(/^https?:\/\//, '')}</span><Link href="/connect">Đổi server</Link></div>{error && <Notice danger>{error}</Notice>}<form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); try { if (await login(identifier, password, admin)) router.push(admin ? '/admin' : '/workspaces'); } finally { setBusy(false); } }}><Field label="Email hoặc tên đăng nhập" value={identifier} onChange={event => setIdentifier(event.target.value)} required autoComplete="username" placeholder="ban@congty.vn" /><Field label="Mật khẩu" type="password" value={password} onChange={event => setPassword(event.target.value)} required autoComplete="current-password" /><Action type="submit" busy={busy}>Đăng nhập <ArrowRight size={15} /></Action></form><p className="auth-form-footer">{admin ? <button onClick={() => setAdmin(false)}>Đăng nhập thành viên</button> : <>Chưa có tài khoản? <Link href="/auth/register">Tạo tài khoản</Link><br /><button className="mt-3" onClick={() => setAdmin(true)}>Đăng nhập quản trị hệ thống</button></>}</p></div></AccessFrame>;
}

export function RegisterScreen() {
  const { register, error, serverInfo } = useSession();
  const [form, setForm] = useState({ name: '', username: '', email: '', password: '' });
  const [busy, setBusy] = useState(false);
  const router = useRouter();
  const change = (key: keyof typeof form) => (event: React.ChangeEvent<HTMLInputElement>) => setForm(value => ({ ...value, [key]: event.target.value }));
  return <AccessFrame><div className="auth-form"><p className="eyebrow">CÙNG NHAU BẮT ĐẦU</p><h2>Tạo tài khoản LANDrop</h2><p>Tài khoản được tạo trên server {serverInfo.serverUrl.replace(/^https?:\/\//, '')}. Chủ workspace sẽ thêm bạn bằng email.</p>{error && <Notice danger>{error}</Notice>}<form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); try { if (await register(form.name, form.username, form.email, form.password)) router.push('/workspaces'); } finally { setBusy(false); } }}><Field label="Tên hiển thị" value={form.name} onChange={change('name')} required maxLength={100} autoComplete="name" /><Field label="Tên đăng nhập" value={form.username} onChange={change('username')} required pattern="[A-Za-z0-9_-]{3,30}" minLength={3} maxLength={30} autoComplete="username" hint="3–30 ký tự: chữ, số, dấu gạch ngang hoặc gạch dưới." /><Field label="Email" type="email" value={form.email} onChange={change('email')} required autoComplete="email" /><Field label="Mật khẩu" type="password" value={form.password} onChange={change('password')} required minLength={8} autoComplete="new-password" hint="Ít nhất 8 ký tự." /><Action type="submit" busy={busy}>Tạo tài khoản <ArrowRight size={15} /></Action></form><p className="auth-form-footer">Đã có tài khoản? <Link href="/auth/login">Đăng nhập</Link></p></div></AccessFrame>;
}

export function LandingScreen() {
  const { ready, user } = useSession();
  const router = useRouter();
  useEffect(() => { if (ready) router.replace(user ? '/workspaces' : '/connect'); }, [ready, user, router]);
  return <div className="boot-state"><span className="brand-mark">L</span><p>Đang mở LANDrop…</p></div>;
}
