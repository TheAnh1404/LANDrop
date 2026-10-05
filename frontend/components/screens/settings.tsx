'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Save, Settings2 } from 'lucide-react';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { errorMessage } from '@/lib/api/client';
import { Action, Dialog, Feedback, Field, Heading, Notice, Panel, Success, Tag } from '../system/primitives';

export function SettingsScreen({ id }: { id: string }) {
  const { workspace, loading, error, refresh, mutate } = useWorkspace();
  const { mode } = useSession();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const [saved, setSaved] = useState(false);
  const [danger, setDanger] = useState<'delete' | 'leave' | null>(null);
  const router = useRouter();
  useEffect(() => { setName(workspace?.name || ''); setDescription(workspace?.description || ''); }, [workspace?.name, workspace?.description]);
  const editable = workspace && ['OWNER', 'ADMIN'].includes(workspace.role);
  return <><Heading eyebrow="QUẢN LÝ WORKSPACE" title="Cài đặt không gian" description="Cập nhật thông tin chung và kiểm tra vai trò của bạn trong nhóm." /><Feedback loading={loading} error={error} retry={refresh} />{workspace && <div className="section-gap settings-panel"><Panel title="Thông tin chung" action={<Tag>{workspace.role}</Tag>}><form className="panel-body form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); setFailure(''); setSaved(false); try { await mutate(`/api/workspaces/${id}`, 'PATCH', { name: name.trim(), description: description.trim() || null }); setSaved(true); } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>{mode === 'demo' && <Notice>Cài đặt trong demo là dữ liệu mẫu. Kết nối server để lưu thay đổi thật.</Notice>}{failure && <Notice danger>{failure}</Notice>}{saved && <Success>Server đã lưu thông tin workspace.</Success>}<Field label="Tên không gian" value={name} onChange={event => { setName(event.target.value); setSaved(false); }} required maxLength={100} disabled={!editable || mode === 'demo'} /><label className="field"><span>Mô tả</span><textarea rows={4} value={description} onChange={event => { setDescription(event.target.value); setSaved(false); }} maxLength={2000} disabled={!editable || mode === 'demo'} /></label><div className="form-actions"><Action type="submit" busy={busy} disabled={!editable || mode === 'demo' || !name.trim()}><Save size={15} />Lưu thay đổi</Action></div></form></Panel><Panel title="Thông tin truy cập"><div className="panel-body"><dl className="detail-grid"><dt>Mã workspace</dt><dd>{workspace.id}</dd><dt>Vai trò hiện tại</dt><dd>{workspace.role}</dd><dt>Ngày tạo</dt><dd>{new Date(workspace.createdAt).toLocaleDateString('vi-VN')}</dd><dt>Nội dung file</dt><dd>Nằm trên thiết bị nguồn trong LAN</dd></dl></div></Panel><Panel title={workspace.role === 'OWNER' ? 'Xóa workspace' : 'Rời không gian'} description={workspace.role === 'OWNER' ? 'Ảnh hưởng toàn bộ thành viên và metadata của nhóm.' : 'Bạn sẽ mất quyền truy cập các kênh và tài liệu của nhóm.'}><div className="panel-body"><Action tone="danger" disabled={mode === 'demo'} onClick={() => { setDanger(workspace.role === 'OWNER' ? 'delete' : 'leave'); setFailure(''); }}>{workspace.role === 'OWNER' ? 'Xóa không gian' : 'Rời nhóm'}</Action></div></Panel></div>}{danger && <Dialog title={danger === 'delete' ? 'Xác nhận xóa workspace' : 'Xác nhận rời nhóm'} description={danger === 'delete' ? 'Server sẽ xóa workspace cùng membership, kênh và metadata liên quan. Nội dung file trên thiết bị không bị xóa.' : 'Bạn mất quyền truy cập workspace sau khi server xác nhận.'} onClose={() => !busy && setDanger(null)}><div className="dialog-content">{failure && <Notice danger>{failure}</Notice>}<div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setDanger(null)}>Hủy</Action><Action tone="danger" busy={busy} onClick={async () => { setBusy(true); try { await mutate(danger === 'delete' ? `/api/workspaces/${id}` : `/api/workspaces/${id}/leave`, danger === 'delete' ? 'DELETE' : 'POST'); router.push('/workspaces'); } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>Xác nhận {danger === 'delete' ? 'xóa' : 'rời nhóm'}</Action></div></div></Dialog>}</>;
}
