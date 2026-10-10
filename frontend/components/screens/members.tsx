'use client';

import React, { useState } from 'react';
import { Check, Copy, QrCode, UserPlus, Users } from 'lucide-react';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { WorkspaceMember } from '@/lib/types';
import { errorMessage } from '@/lib/api/client';
import { Action, Dialog, Empty, Feedback, Field, Heading, Initials, Notice, Panel, SearchBox, Tag } from '../system/primitives';

export function MembersScreen({ id }: { id: string }) {
  const { members, workspace, loading, error, refresh, mutate } = useWorkspace();
  const { mode, user, serverInfo } = useSession();
  const [query, setQuery] = useState('');
  const [add, setAdd] = useState(false);
  const [email, setEmail] = useState('');
  const [remove, setRemove] = useState<WorkspaceMember | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const [showQr, setShowQr] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);

  const editable = workspace && ['OWNER', 'ADMIN'].includes(workspace.role);
  const filtered = members.filter(member => `${member.name} ${member.email}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()));
  const joinUrl = typeof window !== 'undefined' ? `${window.location.origin}/w/${id}` : `/w/${id}`;
  const qrEndpoint = `${serverInfo.serverUrl.replace(/\/+$/, '')}/api/workspaces/${id}/qr`;

  const mutateRole = async (member: WorkspaceMember, role: string) => {
    setBusy(true);
    setFailure('');
    try {
      await mutate(`/api/workspaces/${id}/members/${member.userId}`, 'PATCH', { role });
    } catch (err) {
      setFailure(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Heading
        eyebrow="NHỮNG NGƯỜI CÙNG LÀM VIỆC"
        title="Thành viên"
        description="Quyền truy cập và vai trò được quản lý theo từng không gian làm việc."
        action={
          <div className="flex items-center gap-2">
            <Action tone="secondary" onClick={() => setShowQr(true)}>
              <QrCode size={15} /> Link & Mã QR
            </Action>
            {editable && (
              <Action
                onClick={() => {
                  setAdd(true);
                  setFailure('');
                }}
              >
                <UserPlus size={15} /> Thêm thành viên
              </Action>
            )}
          </div>
        }
      />

      <Feedback loading={loading} error={error} retry={refresh} />
      {failure && !add && !remove && <Notice danger>{failure}</Notice>}

      <div className="toolbar">
        <Tag>{members.length} thành viên đã tải</Tag>
        <SearchBox value={query} onChange={setQuery} placeholder="Tìm thành viên…" />
      </div>

      <Panel>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>THÀNH VIÊN</th>
                <th>VAI TRÒ</th>
                <th>NGÀY THAM GIA</th>
                <th>HIỆN DIỆN</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {filtered.map(member => (
                <tr key={member.id}>
                  <td>
                    <div className="name-cell">
                      <Initials name={member.name} />
                      <div>
                        <strong>
                          {member.name}
                          {member.userId === user?.id ? ' (bạn)' : ''}
                        </strong>
                        <small>{member.email || `ID ${member.userId.slice(0, 8)}`}</small>
                      </div>
                    </div>
                  </td>
                  <td>
                    <Tag tone={member.role === 'OWNER' ? 'green' : member.role === 'ADMIN' ? 'blue' : 'neutral'}>
                      {member.role === 'OWNER' ? 'Chủ nhóm' : member.role === 'ADMIN' ? 'Quản trị nhóm' : 'Thành viên'}
                    </Tag>
                  </td>
                  <td>{new Date(member.joinedAt).toLocaleDateString('vi-VN')}</td>
                  <td>
                    <Tag>{mode === 'demo' ? `${member.status} · mẫu` : 'Chưa có presence'}</Tag>
                  </td>
                  <td className="actions-cell">
                    {mode === 'live' && workspace?.role === 'OWNER' && member.role !== 'OWNER' && member.userId !== user?.id && (
                      <Action
                        tone="secondary"
                        disabled={busy}
                        onClick={() => void mutateRole(member, member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN')}
                      >
                        {member.role === 'ADMIN' ? 'Đổi thành thành viên' : 'Đặt quản trị'}
                      </Action>
                    )}
                    {mode === 'live' && editable && member.role !== 'OWNER' && member.userId !== user?.id && (workspace?.role === 'OWNER' || member.role === 'MEMBER') && (
                      <Action
                        tone="ghost"
                        disabled={busy}
                        onClick={() => {
                          setRemove(member);
                          setFailure('');
                        }}
                      >
                        Loại khỏi nhóm
                      </Action>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!loading && !filtered.length && (
          <Empty
            icon={<Users size={23} />}
            title="Chưa có thành viên phù hợp"
            description="Thử tên khác hoặc thêm một tài khoản đã đăng ký."
          />
        )}
      </Panel>

      {/* Add Member Modal */}
      {add && (
        <Dialog
          title="Thêm thành viên"
          description="Nhập email tài khoản đã đăng ký. Người được thêm có vai trò MEMBER ngay sau khi server xác nhận."
          onClose={() => !busy && setAdd(false)}
        >
          <form
            className="form-stack"
            onSubmit={async event => {
              event.preventDefault();
              setBusy(true);
              setFailure('');
              try {
                await mutate(`/api/workspaces/${id}/members`, 'POST', { email: email.trim() });
                setAdd(false);
                setEmail('');
              } catch (err) {
                setFailure(errorMessage(err));
              } finally {
                setBusy(false);
              }
            }}
          >
            {mode === 'demo' && <Notice>Thêm tài khoản cần kết nối server thật.</Notice>}
            {failure && <Notice danger>{failure}</Notice>}
            <Field
              label="Email thành viên"
              type="email"
              value={email}
              onChange={event => setEmail(event.target.value)}
              required
              maxLength={255}
              placeholder="thanhvien@congty.vn"
            />
            <div className="form-actions">
              <Action tone="secondary" disabled={busy} onClick={() => setAdd(false)}>
                Hủy
              </Action>
              <Action type="submit" busy={busy} disabled={mode === 'demo'}>
                Thêm thành viên
              </Action>
            </div>
          </form>
        </Dialog>
      )}

      {/* Remove Member Modal */}
      {remove && (
        <Dialog
          title="Loại thành viên khỏi nhóm?"
          description={`${remove.name} sẽ mất quyền truy cập workspace. Lịch sử tin nhắn và tài liệu của họ được giữ.`}
          onClose={() => !busy && setRemove(null)}
        >
          <div className="dialog-content">
            {failure && <Notice danger>{failure}</Notice>}
            <div className="form-actions">
              <Action tone="secondary" disabled={busy} onClick={() => setRemove(null)}>
                Hủy
              </Action>
              <Action
                tone="danger"
                busy={busy}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await mutate(`/api/workspaces/${id}/members/${remove.userId}`, 'DELETE');
                    setRemove(null);
                  } catch (err) {
                    setFailure(errorMessage(err));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Loại khỏi nhóm
              </Action>
            </div>
          </div>
        </Dialog>
      )}

      {/* Share & QR Code Modal */}
      {showQr && (
        <Dialog
          title="Mời thành viên qua liên kết & Mã QR"
          description="Chia sẻ liên kết trực tiếp hoặc đưa mã QR để người khác quét bằng điện thoại và tham gia nhóm."
          onClose={() => setShowQr(false)}
        >
          <div className="dialog-content space-y-4">
            <div className="p-3 bg-slate-950/80 rounded-xl border border-slate-800 flex items-center justify-between gap-2">
              <code className="text-xs font-mono text-cyan-300 truncate select-all">{joinUrl}</code>
              <button
                type="button"
                onClick={() => {
                  navigator.clipboard.writeText(joinUrl);
                  setCopiedLink(true);
                  setTimeout(() => setCopiedLink(false), 2000);
                }}
                className="px-2.5 py-1 text-xs rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors flex items-center gap-1 shrink-0 font-medium"
              >
                {copiedLink ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
                <span>{copiedLink ? 'Đã chép' : 'Sao chép'}</span>
              </button>
            </div>

            <div className="p-4 bg-slate-950 rounded-2xl border border-slate-800 text-center space-y-2.5">
              <div className="bg-white p-2.5 rounded-xl w-fit mx-auto shadow-xl">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={qrEndpoint}
                  alt="Mã QR tham gia nhóm"
                  className="w-48 h-48 object-contain"
                  onError={e => {
                    (e.target as HTMLElement).style.display = 'none';
                  }}
                />
              </div>
              <span className="text-[11px] text-slate-400 block font-medium">
                Chế độ tham gia:{' '}
                <strong className="text-slate-200 font-semibold">
                  {workspace?.joinPolicy === 'PASSWORD_PROTECTED'
                    ? 'Yêu cầu mật khẩu'
                    : workspace?.joinPolicy === 'OPEN_LAN'
                    ? 'Mở tự do mạng LAN'
                    : 'Chỉ lời mời'}
                </strong>
              </span>
            </div>

            <div className="form-actions">
              <Action tone="secondary" onClick={() => setShowQr(false)}>
                Đóng
              </Action>
            </div>
          </div>
        </Dialog>
      )}
    </>
  );
}
