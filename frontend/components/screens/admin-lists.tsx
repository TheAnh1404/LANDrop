'use client';

import React, { useEffect, useState } from 'react';
import { Monitor, Plus, RefreshCw } from 'lucide-react';
import { AdminUser, AdminWorkspace } from '@/lib/api/admin-models';
import { errorMessage } from '@/lib/api/client';
import { useAdminResource as useAdmin } from '@/lib/data/use-admin-resource';
import { useSession } from '@/lib/session/session-provider';
import { MOCK_ADMIN_CLIENTS, MOCK_ADMIN_LOGS, MOCK_ADMIN_SESSIONS, MOCK_ADMIN_USERS, MOCK_ADMIN_WORKSPACES } from '@/lib/mock-data';
import { Action, Dialog, Empty, Feedback, Field, Heading, Initials, Notice, Panel, SearchBox, Tag } from '../system/primitives';
import { AdminUserEditor, AdminWorkspaceEditor } from './admin-editors';

type Row = Record<string, unknown>;
const text = (row: Row, ...keys: string[]) => { for (const key of keys) if (row[key] !== undefined && row[key] !== null && row[key] !== '') return String(row[key]); return '—'; };
const rows = (response: unknown): Row[] => Array.isArray(response) ? response : response && typeof response === 'object' ? rows((response as Row).data || (response as Row).items) : [];
const stamp = (input: string) => input === '—' ? input : Number.isNaN(new Date(input).getTime()) ? input : new Date(input).toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });

type Resource = 'users' | 'workspaces' | 'clients' | 'sessions' | 'logs';
const demoLists: Record<Resource, Row[]> = {
  users: MOCK_ADMIN_USERS.map(row => ({ ...row, username: row.email.split('@')[0], status: row.status.toUpperCase(), displayName: row.name, systemRole: row.role === 'SYSTEM_ADMIN' ? 'SYSTEM_ADMIN' : 'USER', activeSessionsCount: row.sessionsCount })),
  workspaces: MOCK_ADMIN_WORKSPACES.map(row => ({ ...row, ownerId: '', ownerUsername: row.ownerName, memberCount: row.membersCount })),
  clients: MOCK_ADMIN_CLIENTS.map(row => ({ ...row, deviceName: `${row.browser} · ${row.os}`, remoteAddress: row.ipAddress, username: row.userName, authenticated: row.status !== 'unauthenticated' })),
  sessions: MOCK_ADMIN_SESSIONS.map(row => ({ ...row, username: row.userName, deviceName: row.deviceInfo, isActive: row.status === 'active', revoked: row.status === 'revoked' })),
  logs: MOCK_ADMIN_LOGS.map(row => ({ ...row, eventType: row.event, details: row.description }))
};
const descriptions: Record<Resource, { title: string; description: string }> = {
  users: { title: 'Người dùng', description: 'Tài khoản đăng ký, vai trò hệ thống và quyền đăng nhập.' },
  workspaces: { title: 'Các không gian', description: 'Theo dõi nhóm làm việc và người chịu trách nhiệm.' },
  clients: { title: 'Thiết bị kết nối', description: 'Những client WebSocket đang đăng ký với server tích hợp.' },
  sessions: { title: 'Phiên đăng nhập', description: 'Theo dõi thời hạn và thu hồi phiên xác thực của tài khoản.' },
  logs: { title: 'Nhật ký vận hành', description: 'Sự kiện hệ thống, hành động quản trị và hoạt động xác thực.' }
};
type Column = { label: string; render: (row: Row) => React.ReactNode };
const columns: Record<Resource, Column[]> = {
  users: [{ label: 'NGƯỜI DÙNG', render: row => <div className="name-cell"><Initials name={text(row, 'displayName', 'username')} /><div><strong>{text(row, 'displayName', 'username')}</strong><small>{text(row, 'email')}</small><small className="select-all" title="Mã tài khoản để chỉ định chủ workspace">{text(row, 'id')}</small></div></div> }, { label: 'VAI TRÒ', render: row => <Tag tone={text(row, 'systemRole') === 'SYSTEM_ADMIN' ? 'blue' : 'neutral'}>{text(row, 'systemRole')}</Tag> }, { label: 'TRẠNG THÁI', render: row => <Tag tone={text(row, 'status').toUpperCase() === 'ACTIVE' ? 'green' : 'amber'}>{text(row, 'status')}</Tag> }, { label: 'PHIÊN HOẠT ĐỘNG', render: row => text(row, 'activeSessionsCount') }, { label: 'ĐĂNG KÝ', render: row => stamp(text(row, 'createdAt')) }],
  workspaces: [{ label: 'KHÔNG GIAN', render: row => <div className="name-cell"><Initials name={text(row, 'name')} /><strong>{text(row, 'name')}</strong></div> }, { label: 'CHỦ NHÓM', render: row => text(row, 'ownerUsername') }, { label: 'THÀNH VIÊN', render: row => text(row, 'memberCount') }, { label: 'NGÀY TẠO', render: row => stamp(text(row, 'createdAt')) }],
  clients: [{ label: 'THIẾT BỊ', render: row => <div className="name-cell"><Monitor size={19} /><div><strong>{text(row, 'deviceName', 'clientInstanceId')}</strong><small>{text(row, 'platform', 'os')}</small></div></div> }, { label: 'NGƯỜI DÙNG', render: row => text(row, 'username', 'userId') }, { label: 'ĐỊA CHỈ', render: row => text(row, 'remoteAddress') }, { label: 'XÁC THỰC', render: row => <Tag tone={row.authenticated ? 'green' : 'amber'}>{row.authenticated ? 'Đã xác thực' : 'Chưa xác thực'}</Tag> }, { label: 'HEARTBEAT', render: row => stamp(text(row, 'lastHeartbeatAt', 'lastHeartbeat')) }],
  sessions: [{ label: 'TÀI KHOẢN', render: row => text(row, 'username', 'userId') }, { label: 'THIẾT BỊ', render: row => text(row, 'deviceName', 'deviceId') }, { label: 'TRẠNG THÁI', render: row => <Tag tone={row.isActive ? 'green' : 'neutral'}>{row.revoked ? 'Đã thu hồi' : row.isActive ? 'Hoạt động' : 'Hết hạn'}</Tag> }, { label: 'TẠO LÚC', render: row => stamp(text(row, 'createdAt')) }, { label: 'HẾT HẠN', render: row => stamp(text(row, 'expiresAt')) }],
  logs: [{ label: 'THỜI GIAN', render: row => stamp(text(row, 'timestamp')) }, { label: 'MỨC ĐỘ', render: row => <Tag tone={row.level === 'ERROR' ? 'red' : row.level === 'WARN' ? 'amber' : 'neutral'}>{text(row, 'level')}</Tag> }, { label: 'SỰ KIỆN', render: row => text(row, 'eventType') }, { label: 'NGƯỜI THỰC HIỆN', render: row => text(row, 'actor') }, { label: 'NỘI DUNG', render: row => <span className="inline-block max-w-[360px] whitespace-normal leading-6">{text(row, 'details')}</span> }]
};

export function AdminListScreen({ resource }: { resource: Resource }) {
  const { request, mode, user } = useSession();
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [level, setLevel] = useState('all');
  const [serverQuery, setServerQuery] = useState('');
  const serverSearch = ['users', 'workspaces', 'logs'].includes(resource);
  useEffect(() => {
    const timer = setTimeout(() => { setServerQuery(query); setOffset(0); }, 300);
    return () => clearTimeout(timer);
  }, [query]);
  const params = new URLSearchParams({ limit: '100', offset: String(offset) });
  if (serverSearch && serverQuery) params.set('search', serverQuery);
  if (resource === 'logs' && level !== 'all') params.set('level', level);
  const result = useAdmin<unknown>(`/api/admin/${resource}?${params}`, demoLists[resource]);
  const list = rows(result.data);
  const filtered = list.filter(row => (level === 'all' || row.level === level) && Object.values(row).some(value => String(value ?? '').toLocaleLowerCase().includes(query.toLocaleLowerCase())));
  const [confirmation, setConfirmation] = useState<{ title: string; description: string; route: string } | null>(null);
  const [create, setCreate] = useState(false);
  const [name, setName] = useState('');
  const [owner, setOwner] = useState('');
  const [editor, setEditor] = useState<{ type: 'users' | 'workspaces'; row: Row } | null>(null);
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const begin = (title: string, description: string, route: string) => { setFailure(''); setConfirmation({ title, description, route }); };
  return <><Heading eyebrow="QUẢN TRỊ HỆ THỐNG" title={descriptions[resource].title} description={descriptions[resource].description} action={<><Action tone="secondary" onClick={result.refresh}><RefreshCw size={15} />Làm mới</Action>{resource === 'workspaces' && <Action onClick={() => { setCreate(true); setFailure(''); }}><Plus size={15} />Tạo không gian</Action>}</>} /><Feedback loading={result.loading} error={result.error} retry={result.refresh} /><div className="toolbar"><div className="toolbar-actions"><Tag>{list.length} bản ghi đã tải</Tag>{resource === 'logs' && <select className="plain-select" aria-label="Lọc mức độ nhật ký" value={level} onChange={event => { setLevel(event.target.value); setOffset(0); }}><option value="all">Tất cả mức độ</option>{['INFO', 'WARN', 'ERROR', 'DEBUG'].map(value => <option key={value}>{value}</option>)}</select>}</div><SearchBox value={query} onChange={setQuery} placeholder={serverSearch ? "Tìm trên server…" : "Tìm trong danh sách đã tải…"} /></div><Panel><div className="table-wrap"><table className="data-table"><thead><tr>{columns[resource].map(column => <th key={column.label}>{column.label}</th>)}{['users', 'workspaces', 'sessions', 'clients'].includes(resource) && <th />}</tr></thead><tbody>{filtered.map((row, index) => <tr key={text(row, 'id', 'connectionId') === '—' ? index : text(row, 'id', 'connectionId')}>{columns[resource].map(column => <td key={column.label}>{column.render(row)}</td>)}{['users', 'workspaces', 'sessions', 'clients'].includes(resource) && <td className="actions-cell">{(resource === 'users' || resource === 'workspaces') && <Action tone="secondary" onClick={() => setEditor({ type: resource, row })}>{resource === 'users' ? 'Chi tiết' : 'Quản lý'}</Action>}{mode === 'live' && resource === 'users' && row.id !== user?.id && <Action tone="secondary" onClick={() => { const disable = text(row, 'status').toUpperCase() === 'ACTIVE'; begin(disable ? 'Khóa tài khoản?' : 'Mở khóa tài khoản?', `Thay đổi quyền đăng nhập của ${text(row, 'displayName', 'username')}. Server sẽ kiểm tra quyền quản trị.`, `/api/admin/users/${row.id}/${disable ? 'disable' : 'enable'}`); }}>{text(row, 'status').toUpperCase() === 'ACTIVE' ? 'Khóa tài khoản' : 'Mở khóa'}</Action>}{mode === 'live' && resource === 'sessions' && row.isActive === true && <Action tone="secondary" onClick={() => begin('Thu hồi phiên đăng nhập?', `Phiên của ${text(row, 'username')} sẽ bị thu hồi trên server.`, `/api/admin/sessions/${row.id}/revoke`)}>Thu hồi phiên</Action>}{mode === 'live' && resource === 'clients' && <Action tone="secondary" onClick={() => begin('Ngắt kết nối thiết bị?', `Ngắt WebSocket của ${text(row, 'deviceName')}.`, `/api/admin/clients/${row.connectionId}/disconnect`)}>Ngắt kết nối</Action>}</td>}</tr>)}</tbody></table></div>{!result.loading && !filtered.length && <Empty title="Chưa có bản ghi phù hợp" description="Làm mới hoặc thay đổi bộ lọc để xem dữ liệu." />}<div className="table-footer flex items-center justify-between"><span>{filtered.length} bản ghi hiển thị{mode === 'demo' ? ' · dữ liệu mẫu' : ''}</span>{mode === 'live' && ['users', 'sessions', 'logs'].includes(resource) && <div className="flex gap-2"><Action tone="ghost" disabled={offset === 0 || result.loading} onClick={() => setOffset(value => Math.max(0, value - 100))}>Trang trước</Action><Action tone="ghost" disabled={list.length < 100 || result.loading} onClick={() => setOffset(value => value + 100)}>Trang sau</Action></div>}</div></Panel>{confirmation && <Dialog title={confirmation.title} description={confirmation.description} onClose={() => !busy && setConfirmation(null)}><div className="dialog-content">{failure && <Notice danger>{failure}</Notice>}<div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setConfirmation(null)}>Hủy</Action><Action tone="danger" busy={busy} onClick={async () => { setBusy(true); try { const response = await request<{ success?: boolean }>(confirmation.route, { method: 'POST', body: JSON.stringify({ reason: 'Administrator action' }) }); if (response.success === false) throw new Error('Server không thực hiện được thao tác.'); setConfirmation(null); result.refresh(); } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>Xác nhận</Action></div></div></Dialog>}{create && <Dialog title="Tạo không gian từ quản trị" description="Chỉ định tài khoản đã đăng ký làm chủ workspace." onClose={() => !busy && setCreate(false)}><form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); setFailure(''); try { await request('/api/admin/workspaces', { method: 'POST', body: JSON.stringify({ name: name.trim(), ownerId: owner.trim() }) }); setCreate(false); setName(''); setOwner(''); result.refresh(); } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>{mode === 'demo' && <Notice>Thao tác này cần server thật.</Notice>}{failure && <Notice danger>{failure}</Notice>}<Field label="Tên workspace" value={name} onChange={event => setName(event.target.value)} required maxLength={100} /><Field label="Mã tài khoản chủ nhóm (UUID)" value={owner} onChange={event => setOwner(event.target.value)} required pattern="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" hint="Lấy UUID tài khoản từ danh sách người dùng hoặc backend." /><div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setCreate(false)}>Hủy</Action><Action type="submit" busy={busy} disabled={mode === 'demo'}>Tạo không gian</Action></div></form></Dialog>}{editor?.type === 'users' && <AdminUserEditor key={String(editor.row.id)} account={editor.row as unknown as AdminUser} onClose={() => setEditor(null)} onChanged={result.refresh} />}{editor?.type === 'workspaces' && <AdminWorkspaceEditor key={String(editor.row.id)} workspace={editor.row as unknown as AdminWorkspace} onClose={() => setEditor(null)} onChanged={result.refresh} />}</>;
}
