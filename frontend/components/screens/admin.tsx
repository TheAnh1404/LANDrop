'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { Activity, ArrowDownUp, ArrowRight, FolderOpen, Monitor, Plus, RefreshCw, Save, Server, ShieldCheck, Users } from 'lucide-react';
import { useSession } from '@/lib/session/session-provider';
import { errorMessage } from '@/lib/api/client';
import { MOCK_ADMIN_OVERVIEW, MOCK_SERVER_CONFIG } from '@/lib/mock-data';
import { Action, Dialog, Empty, Feedback, Field, Heading, Initials, Notice, Panel, SearchBox, Stat, Success, Tag } from '../system/primitives';

type Row = Record<string, unknown>;
const text = (row: Row, ...keys: string[]) => { for (const key of keys) if (row[key] !== undefined && row[key] !== null && row[key] !== '') return String(row[key]); return '—'; };
const count = (row: Row | null, key: string) => row && typeof row[key] === 'number' ? Number(row[key]) : '—';
function useAdmin<T>(route: string, seed: T) {
  const { mode, request } = useSession();
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setData(null);
    if (mode === 'demo') { setData(seed); setLoading(false); return; }
    request<T>(route, { signal: controller.signal }).then(response => { if (!controller.signal.aborted) setData(response); }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [route, mode, request, revision]);
  return { data, loading, error, refresh: () => setRevision(r => r + 1) };
}
const demoOverview: Row = { status: 'RUNNING', uptimeFormatted: MOCK_ADMIN_OVERVIEW.uptime, connectedClients: MOCK_ADMIN_OVERVIEW.connectedClients, registeredUsers: MOCK_ADMIN_OVERVIEW.registeredUsers, activeSessions: MOCK_ADMIN_OVERVIEW.activeSessions, workspaceCount: MOCK_ADMIN_OVERVIEW.totalWorkspaces, activeTransfers: MOCK_ADMIN_OVERVIEW.activeTransfers };
const demoInfo: Row = { host: MOCK_ADMIN_OVERVIEW.bindAddress, httpPort: MOCK_ADMIN_OVERVIEW.port, wsPort: MOCK_ADMIN_OVERVIEW.wsPort, protocolVersion: 1, environment: 'demo', serverVersion: 'Dữ liệu mẫu' };

export function AdminOverviewScreen() {
  const overview = useAdmin<Row>('/api/admin/dashboard', demoOverview);
  const info = useAdmin<Row>('/api/admin/server/info', demoInfo);
  const { mode } = useSession();
  return <><Heading eyebrow="TRUNG TÂM VẬN HÀNH" title="Tổng quan hệ thống" description="Theo dõi server, tài khoản và những kết nối đang được quản lý." action={<Action tone="secondary" onClick={() => { overview.refresh(); info.refresh(); }}><RefreshCw size={15} />Làm mới</Action>} /><Feedback loading={overview.loading} error={overview.error} retry={overview.refresh} /><div className="hero"><div className="hero-copy"><p className="eyebrow">LANDROP SERVER</p><h2>{overview.data ? 'Hệ thống đang phản hồi.' : 'Kiểm tra trạng thái hệ thống.'}</h2><p>{overview.data ? `Uptime: ${text(overview.data, 'uptimeFormatted')} · Trạng thái: ${text(overview.data, 'status')}` : 'Các chỉ số xuất hiện sau khi server xác nhận quyền quản trị.'}</p><Link className="action action-primary" href="/admin/server">Cấu hình server <ArrowRight size={15} /></Link></div><div className="hero-diagram"><span className="hero-node"><Monitor size={26} /></span><span className="hero-line" /><span className="hero-node hero-center"><Server size={34} /></span><span className="hero-line" /><span className="hero-node"><ShieldCheck size={26} /></span></div></div><div className="stats-grid"><Stat label="Thiết bị kết nối" value={count(overview.data, 'connectedClients')} icon={<Monitor size={16} />} detail="WebSocket clients" /><Stat label="Người dùng" value={count(overview.data, 'registeredUsers')} icon={<Users size={16} />} detail="Tài khoản đã đăng ký" /><Stat label="Phiên đang hoạt động" value={count(overview.data, 'activeSessions')} icon={<ShieldCheck size={16} />} detail="Phiên xác thực trên server" /><Stat label="Không gian" value={count(overview.data, 'workspaceCount')} icon={<FolderOpen size={16} />} detail="Workspace trên hệ thống" /></div><div className="split-grid"><Panel title="Thông tin server" description={mode === 'demo' ? 'Thông số mẫu' : 'Thông số hiện tại do backend cung cấp'}><div className="panel-body"><Feedback loading={info.loading} error={info.error} retry={info.refresh} />{info.data && <dl className="detail-grid">{[['Địa chỉ bind', 'host'], ['Cổng HTTP', 'httpPort'], ['Cổng WebSocket', 'wsPort'], ['Protocol', 'protocolVersion'], ['Phiên bản', 'serverVersion'], ['Môi trường', 'environment']].map(([label, key]) => <React.Fragment key={key}><dt>{label}</dt><dd>{text(info.data!, key)}</dd></React.Fragment>)}</dl>}</div></Panel><Panel title="Quản lý hoạt động"><div className="overview-links">{[{ title: 'Tài khoản và quyền', description: 'Khóa, mở khóa và xem người dùng', href: '/admin/users', icon: <Users size={19} /> }, { title: 'Thiết bị và phiên', description: 'Theo dõi kết nối, thu hồi phiên xác thực', href: '/admin/sessions', icon: <ShieldCheck size={19} /> }, { title: 'Nhật ký vận hành', description: 'Sự kiện quản trị được ghi trên server', href: '/admin/logs', icon: <Activity size={19} /> }].map(item => <Link className="overview-link" href={item.href} key={item.href}>{item.icon}<div><strong>{item.title}</strong><p>{item.description}</p></div><ArrowRight size={14} /></Link>)}</div></Panel></div></>;
}

export { AdminListScreen } from './admin-lists';

type Config = { host: string; httpPort: number; wsPort: number; heartbeatIntervalMs: number; heartbeatTimeoutMs: number; logLevel: string; environment: string; protocolVersion?: number };
type ConfigResponse = { current: Config; pending: Config; requiresRestart: boolean };
const demoConfig: ConfigResponse = { current: { host: MOCK_SERVER_CONFIG.bindAddress, httpPort: MOCK_SERVER_CONFIG.port, wsPort: MOCK_SERVER_CONFIG.wsPort, heartbeatIntervalMs: MOCK_SERVER_CONFIG.heartbeatIntervalMs, heartbeatTimeoutMs: MOCK_SERVER_CONFIG.heartbeatTimeoutMs, logLevel: MOCK_SERVER_CONFIG.logLevel, environment: 'demo', protocolVersion: 1 }, pending: { host: MOCK_SERVER_CONFIG.bindAddress, httpPort: MOCK_SERVER_CONFIG.port, wsPort: MOCK_SERVER_CONFIG.wsPort, heartbeatIntervalMs: MOCK_SERVER_CONFIG.heartbeatIntervalMs, heartbeatTimeoutMs: MOCK_SERVER_CONFIG.heartbeatTimeoutMs, logLevel: MOCK_SERVER_CONFIG.logLevel, environment: 'demo', protocolVersion: 1 }, requiresRestart: false };
export function AdminServerScreen() {
  const result = useAdmin<ConfigResponse>('/api/admin/server/config', demoConfig);
  const { mode, request } = useSession();
  const [form, setForm] = useState<Config>(demoConfig.pending);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [failure, setFailure] = useState('');
  useEffect(() => { if (result.data) setForm(result.data.pending); }, [result.data]);
  const change = (key: keyof Config, value: string) => { setSaved(false); setForm(previous => ({ ...previous, [key]: ['httpPort', 'wsPort', 'heartbeatIntervalMs', 'heartbeatTimeoutMs'].includes(key) ? Number(value) : value })); };
  return <><Heading eyebrow="SERVER & MẠNG" title="Cấu hình kết nối" description="Xem cấu hình đang chạy và thiết lập chờ áp dụng cho backend." action={<Action tone="secondary" onClick={result.refresh}><RefreshCw size={15} />Làm mới</Action>} /><Feedback loading={result.loading} error={result.error} retry={result.refresh} />{result.data?.requiresRestart && <Notice>Đã có thay đổi cần khởi động lại backend. Cấu hình hiện tại vẫn đang phục vụ các kết nối.</Notice>}<div className="split-grid"><Panel title="Thiết lập server" description="Thông số được lưu qua API quản trị"><form className="panel-body form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); setFailure(''); setSaved(false); try { if (form.httpPort === form.wsPort) throw new Error('Cổng HTTP và WebSocket phải khác nhau.'); if (form.heartbeatTimeoutMs <= form.heartbeatIntervalMs) throw new Error('Heartbeat timeout phải lớn hơn interval.'); const { protocolVersion, ...body } = form; await request('/api/admin/server/config', { method: 'PUT', body: JSON.stringify(body) }); setSaved(true); result.refresh(); } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>{failure && <Notice danger>{failure}</Notice>}{saved && <Success>Server đã lưu cấu hình.</Success>}{mode === 'demo' && <Notice>Cấu hình mẫu chỉ dành cho trải nghiệm giao diện.</Notice>}<Field label="Địa chỉ bind" value={form.host} onChange={event => change('host', event.target.value)} required disabled={mode === 'demo'} /><div className="grid grid-cols-2 gap-4"><Field label="Cổng HTTP" type="number" min={1024} max={65535} value={form.httpPort} onChange={event => change('httpPort', event.target.value)} required disabled={mode === 'demo'} /><Field label="Cổng WebSocket" type="number" min={1024} max={65535} value={form.wsPort} onChange={event => change('wsPort', event.target.value)} required disabled={mode === 'demo'} /></div><div className="grid grid-cols-2 gap-4"><Field label="Heartbeat interval (ms)" type="number" min={100} value={form.heartbeatIntervalMs} onChange={event => change('heartbeatIntervalMs', event.target.value)} required disabled={mode === 'demo'} /><Field label="Heartbeat timeout (ms)" type="number" min={101} value={form.heartbeatTimeoutMs} onChange={event => change('heartbeatTimeoutMs', event.target.value)} required disabled={mode === 'demo'} /></div><label className="field"><span>Mức độ log</span><select value={form.logLevel} onChange={event => change('logLevel', event.target.value)} disabled={mode === 'demo'}>{['DEBUG', 'INFO', 'WARN', 'ERROR'].map(level => <option key={level}>{level}</option>)}</select></label><div className="form-actions"><Action type="submit" busy={busy} disabled={mode === 'demo' || !result.data}><Save size={15} />Lưu cấu hình</Action></div></form></Panel><Panel title="Cấu hình đang sử dụng"><div className="panel-body"><dl className="detail-grid">{result.data && Object.entries(result.data.current).map(([key, value]) => <React.Fragment key={key}><dt>{key}</dt><dd>{String(value)}</dd></React.Fragment>)}</dl></div></Panel></div></>;
}

export function AdminTransfersScreen() {
  const overview = useAdmin<Row>('/api/admin/dashboard', demoOverview);
  return <><Heading eyebrow="VẬN HÀNH TRUYỀN TỆP" title="Hoạt động truyền tệp" description="Chỉ số điều phối từ server, tách biệt với nội dung đang truyền trên thiết bị." /><Feedback loading={overview.loading} error={overview.error} retry={overview.refresh} /><div className="stats-grid"><Stat label="Phiên truyền server đang quản lý" value={count(overview.data, 'activeTransfers')} icon={<ArrowDownUp size={17} />} detail="Chỉ số từ dashboard" /><Stat label="Client đang kết nối" value={count(overview.data, 'connectedClients')} icon={<Monitor size={17} />} detail="Thiết bị đăng ký với control server" /></div><Panel title="Theo dõi chi tiết"><Empty icon={<ArrowDownUp size={25} />} title="Chi tiết transfer nằm trong runtime thiết bị" description="Backend hiện cung cấp chỉ số tổng và truy vấn theo mã transfer. Chưa có API danh sách transfer toàn hệ thống cho màn hình quản trị." action={<Action tone="secondary" onClick={overview.refresh}><RefreshCw size={15} />Cập nhật chỉ số</Action>} /></Panel></>;
}
