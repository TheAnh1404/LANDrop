'use client';

import { useEffect, useMemo, useState } from 'react';
import { AdminIdentity, AdminMember, AdminUser, AdminWorkspace } from '@/lib/api/admin-models';
import { errorMessage } from '@/lib/api/client';
import { useAdminResource } from '@/lib/data/use-admin-resource';
import { useSession } from '@/lib/session/session-provider';
import { MOCK_MEMBERS } from '@/lib/mock-data';
import { Action, Dialog, Empty, Feedback, Field, Notice, Success, Tag } from '../system/primitives';

const uuidPattern = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';

export function AdminWorkspaceEditor({ workspace, onClose, onChanged }: {
  workspace: AdminWorkspace; onClose: () => void; onChanged: () => void;
}) {
  const { request, mode } = useSession();
  const demoMembers = useMemo<AdminMember[]>(() => (MOCK_MEMBERS[workspace.id] || []).map(member => ({
    userId: member.userId, username: member.email.split('@')[0] || member.name, displayName: member.name,
    role: member.role, joinedAt: member.joinedAt,
  })), [workspace.id]);
  const members = useAdminResource<AdminMember[]>(`/api/admin/workspaces/${workspace.id}/members`, demoMembers);
  const [name, setName] = useState(workspace.name);
  const [description, setDescription] = useState(workspace.description || '');
  const [userId, setUserId] = useState('');
  const [role, setRole] = useState('MEMBER');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [confirm, setConfirm] = useState<{ kind: 'delete' | 'remove'; member?: AdminMember } | null>(null);
  const [confirmName, setConfirmName] = useState('');
  const base = `/api/admin/workspaces/${workspace.id}`;

  async function mutate(route: string, method: string, body?: object) {
    setBusy(true); setError(''); setSaved('');
    try {
      const response = await request<{ success?: boolean }>(route, {
        method, ...(body ? { body: JSON.stringify(body) } : {}),
      });
      if (response?.success === false) throw new Error('Server không thực hiện được thao tác.');
      onChanged();
      return true;
    } catch (failure) { setError(errorMessage(failure)); return false; }
    finally { setBusy(false); }
  }

  return <Dialog title={`Quản lý ${workspace.name}`} description="Thông tin và quyền thành viên của workspace trên toàn hệ thống."
    onClose={() => !busy && onClose()}>
    <div className="dialog-content section-gap">
      {mode === 'demo' && <Notice>Chế độ demo chỉ xem mẫu. Quản lý nhóm cần server thật.</Notice>}
      {error && <Notice danger>{error}</Notice>}
      {saved && <Success>{saved}</Success>}
      <form className="form-stack" onSubmit={async event => {
        event.preventDefault();
        if (await mutate(base, 'PATCH', { name: name.trim(), description: description.trim() || null })) setSaved('Đã lưu thông tin workspace.');
      }}>
        <Field label="Tên workspace" value={name} onChange={event => setName(event.target.value)} required maxLength={120} disabled={busy || mode === 'demo'} />
        <label className="field"><span>Mô tả</span><textarea value={description} onChange={event => setDescription(event.target.value)} maxLength={2000} rows={3} disabled={busy || mode === 'demo'} /></label>
        <p className="muted text-xs">Chủ nhóm: {workspace.ownerUsername || workspace.ownerId}</p>
        <div className="form-actions"><Action type="submit" busy={busy} disabled={mode === 'demo' || !name.trim()}>Lưu thông tin</Action></div>
      </form>
      <section aria-label="Thành viên workspace">
        <h3 className="text-sm font-semibold mb-3">Thành viên</h3>
        <Feedback loading={members.loading} error={members.error} retry={members.refresh} />
        {members.data?.map(member => <div className="list-row px-0" key={member.userId}>
          <div className="row-content"><strong>{member.displayName || member.username}</strong><span className="select-all">{member.userId}</span></div>
          {member.role === 'OWNER' ? <Tag tone="green">Chủ nhóm</Tag> : <>
            <select className="plain-select" aria-label={`Vai trò của ${member.displayName || member.username}`} value={member.role} disabled={busy || mode === 'demo'} onChange={async event => {
              if (await mutate(`${base}/members/${member.userId}`, 'PATCH', { role: event.target.value })) { members.refresh(); setSaved('Đã cập nhật quyền thành viên.'); }
            }}><option value="MEMBER">Thành viên</option><option value="ADMIN">Quản trị nhóm</option></select>
            <Action tone="ghost" disabled={busy || mode === 'demo'} onClick={() => { setError(''); setConfirm({ kind: 'remove', member }); }}>Loại</Action>
          </>}
        </div>)}
        {!members.loading && !members.error && !members.data?.length && <Empty title="Chưa tải được thành viên" description={mode === 'demo' ? 'Danh sách quản trị này cần server thật.' : 'Server chưa trả thành viên của workspace.'} />}
        <form className="form-stack mt-4" onSubmit={async event => {
          event.preventDefault();
          if (await mutate(`${base}/members`, 'POST', { userId: userId.trim(), role })) { setUserId(''); members.refresh(); setSaved('Đã thêm hoặc cập nhật thành viên.'); }
        }}>
          <Field label="Mã tài khoản thành viên (UUID)" value={userId} onChange={event => setUserId(event.target.value)} required pattern={uuidPattern} disabled={busy || mode === 'demo'} hint="Sao chép mã tài khoản từ danh sách người dùng. Tài khoản phải đã đăng ký." />
          <label className="field"><span>Vai trò</span><select value={role} onChange={event => setRole(event.target.value)} disabled={busy || mode === 'demo'}><option value="MEMBER">Thành viên</option><option value="ADMIN">Quản trị nhóm</option></select></label>
          <div className="form-actions"><Action type="submit" busy={busy} disabled={mode === 'demo'}>Lưu thành viên</Action></div>
        </form>
      </section>
      {confirm ? <section className="form-stack" aria-label="Xác nhận thao tác">
        <Notice danger>{confirm.kind === 'delete' ? 'Xóa workspace và dữ liệu liên quan trên server. Thao tác không có chức năng hoàn tác trong giao diện.' : `Loại ${confirm.member?.displayName || confirm.member?.username} khỏi workspace. Tài khoản này sẽ mất quyền truy cập nhóm.`}</Notice>
        {confirm.kind === 'delete' && <Field label={`Nhập tên workspace “${workspace.name}” để xác nhận`} value={confirmName} onChange={event => setConfirmName(event.target.value)} disabled={busy} />}
        <div className="form-actions">
          <Action tone="secondary" disabled={busy} onClick={() => setConfirm(null)}>Hủy</Action>
          <Action tone="danger" busy={busy} disabled={confirm.kind === 'delete' && confirmName !== workspace.name} onClick={async () => {
            if (await mutate(confirm.kind === 'delete' ? base : `${base}/members/${confirm.member!.userId}`, 'DELETE')) {
              if (confirm.kind === 'delete') onClose();
              else { setConfirm(null); members.refresh(); setSaved('Đã loại thành viên khỏi workspace.'); }
            }
          }}>Xác nhận {confirm.kind === 'delete' ? 'xóa workspace' : 'loại thành viên'}</Action>
        </div>
      </section> : <div className="form-actions"><Action tone="danger" disabled={busy || mode === 'demo'} onClick={() => { setError(''); setConfirmName(''); setConfirm({ kind: 'delete' }); }}>Xóa workspace</Action></div>}
    </div>
  </Dialog>;
}

export function AdminUserEditor({ account, onClose, onChanged }: {
  account: AdminUser; onClose: () => void; onChanged: () => void;
}) {
  const { request, mode, user, logout } = useSession();
  const demo = useMemo<AdminIdentity>(() => ({ user: account, devices: [], sessions: [], connections: [] }), [account]);
  const identity = useAdminResource<AdminIdentity>(`/api/admin/users/${account.id}/identity`, demo);
  const [displayName, setDisplayName] = useState(account.displayName || account.username);
  const [role, setRole] = useState(account.systemRole);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [confirm, setConfirm] = useState<'role' | 'revoke' | 'delete' | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const own = user?.id === account.id;
  const base = `/api/admin/users/${account.id}`;
  useEffect(() => { if (identity.data) { setDisplayName(identity.data.user.displayName || identity.data.user.username); setRole(identity.data.user.systemRole); } }, [identity.data]);

  async function mutate(method: string, route: string, body?: object) {
    setBusy(true); setError(''); setSaved('');
    try {
      const response = await request<{ success?: boolean }>(route, { method, ...(body ? { body: JSON.stringify(body) } : {}) });
      if (response?.success === false) throw new Error('Server không thực hiện được thao tác.');
      setConfirm(null); onChanged();
      if (method === 'DELETE') onClose();
      else if (route.endsWith('/revoke-sessions') && own) { onClose(); await logout(); }
      else { identity.refresh(); setSaved('Server đã cập nhật tài khoản.'); }
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  return <Dialog title={`Tài khoản ${account.username}`} description="Thông tin tài khoản, thiết bị và phiên do backend cung cấp."
    onClose={() => !busy && onClose()}>
    <div className="dialog-content section-gap">
      {mode === 'demo' && <Notice>Thông tin mẫu. Thao tác tài khoản cần server thật.</Notice>}
      <Feedback loading={identity.loading} error={identity.error} retry={identity.refresh} />
      {error && <Notice danger>{error}</Notice>}{saved && <Success>{saved}</Success>}
      <dl className="detail-grid"><dt>Email</dt><dd>{account.email}</dd><dt>Mã tài khoản</dt><dd className="select-all">{account.id}</dd><dt>Trạng thái</dt><dd>{identity.data?.user.status || account.status}</dd></dl>
      <form className="form-stack" onSubmit={event => {
        event.preventDefault();
        if (role !== (identity.data?.user.systemRole || account.systemRole)) { setError(''); setConfirm('role'); }
        else void mutate('PATCH', base, { displayName: displayName.trim() });
      }}>
        <Field label="Tên hiển thị" value={displayName} onChange={event => setDisplayName(event.target.value)} required maxLength={255} disabled={busy || mode === 'demo' || !identity.data} />
        <label className="field"><span>Quyền hệ thống</span><select value={role} onChange={event => setRole(event.target.value as AdminUser['systemRole'])} disabled={busy || mode === 'demo' || own || !identity.data}><option value="USER">Thành viên</option><option value="SYSTEM_ADMIN">Quản trị hệ thống</option></select></label>
        {own && <p className="muted text-xs">Không thể hạ quyền hoặc xóa tài khoản đang đăng nhập.</p>}
        <div className="form-actions"><Action type="submit" busy={busy} disabled={mode === 'demo' || !identity.data || !displayName.trim()}>Lưu thay đổi</Action></div>
      </form>
      <section aria-label="Thiết bị và phiên">
        <h3 className="text-sm font-semibold">Thiết bị và phiên</h3>
        {identity.data && <>
          <p className="muted text-xs my-3">{identity.data.devices.length} thiết bị · {identity.data.sessions.length} phiên đã tải · {identity.data.connections.length} kết nối</p>
          {identity.data.devices.map(device => <div className="list-row px-0" key={device.id}><div className="row-content"><strong>{device.deviceName || device.name || device.id}</strong><span>{device.platform || 'Chưa có thông tin nền tảng'}</span></div><Tag>{device.isOnline ? 'Online' : 'Offline'}</Tag></div>)}
          {identity.data.sessions.map(session => <div className="list-row px-0" key={session.id}><div className="row-content"><strong>{session.deviceName || 'Phiên đăng nhập'}</strong><span>Hết hạn: {new Date(session.expiresAt).toLocaleString('vi-VN')}</span></div><Tag tone={session.isActive ? 'green' : 'neutral'}>{session.revoked ? 'Đã thu hồi' : session.isActive ? 'Hoạt động' : 'Hết hạn'}</Tag></div>)}
        </>}
      </section>
      {confirm ? <section className="form-stack" aria-label="Xác nhận thao tác tài khoản">
        <Notice danger>{confirm === 'role' ? `Đổi quyền ${account.username} thành ${role}. SYSTEM_ADMIN có quyền quản lý người dùng, nhóm và cấu hình toàn hệ thống.` : confirm === 'revoke' ? 'Thu hồi mọi phiên và ngắt kết nối của tài khoản. Người dùng phải đăng nhập lại.' : 'Xóa tài khoản khỏi server và thu hồi phiên. Tài khoản còn sở hữu nhóm hoặc nội dung có thể bị backend từ chối xóa.'}</Notice>
        {confirm === 'delete' && <Field label={`Nhập “${account.username}” để xác nhận xóa`} value={confirmation} onChange={event => setConfirmation(event.target.value)} disabled={busy} />}
        <div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setConfirm(null)}>Hủy</Action><Action tone="danger" busy={busy} disabled={confirm === 'delete' && confirmation !== account.username} onClick={() => void mutate(confirm === 'delete' ? 'DELETE' : confirm === 'role' ? 'PATCH' : 'POST', confirm === 'revoke' ? `${base}/revoke-sessions` : base, confirm === 'role' ? { displayName: displayName.trim(), systemRole: role } : undefined)}>Xác nhận</Action></div>
      </section> : <div className="form-actions"><Action tone="secondary" disabled={busy || mode === 'demo' || !identity.data} onClick={() => { setError(''); setConfirm('revoke'); }}>Thu hồi mọi phiên</Action>{!own && <Action tone="danger" disabled={busy || mode === 'demo' || !identity.data} onClick={() => { setError(''); setConfirmation(''); setConfirm('delete'); }}>Xóa tài khoản</Action>}</div>}
    </div>
  </Dialog>;
}
