'use client';

import React, { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  Check,
  Copy,
  Eye,
  EyeOff,
  KeyRound,
  Lock,
  QrCode,
  Save,
  Settings2,
  Share2,
  Shield,
  Zap,
} from 'lucide-react';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { errorMessage } from '@/lib/api/client';
import { Action, Dialog, Feedback, Field, Heading, Notice, Panel, Success, Tag } from '../system/primitives';

export function SettingsScreen({ id }: { id: string }) {
  const { workspace, loading, error, refresh, mutate } = useWorkspace();
  const { mode, serverInfo } = useSession();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const [saved, setSaved] = useState(false);
  const [danger, setDanger] = useState<'delete' | 'leave' | null>(null);

  // Join policy & Passcode state
  const [joinPolicy, setJoinPolicy] = useState<'INVITE_ONLY' | 'PASSWORD_PROTECTED' | 'OPEN_LAN'>('INVITE_ONLY');
  const [joinPassword, setJoinPassword] = useState('');
  const [showPass, setShowPass] = useState(false);
  const [policyBusy, setPolicyBusy] = useState(false);
  const [policyError, setPolicyError] = useState('');
  const [policySaved, setPolicySaved] = useState(false);
  const [copiedLink, setCopiedLink] = useState(false);
  const [showQr, setShowQr] = useState(false);

  const router = useRouter();

  useEffect(() => {
    if (workspace) {
      setName(workspace.name || '');
      setDescription(workspace.description || '');
      setJoinPolicy(workspace.joinPolicy || 'INVITE_ONLY');
    }
  }, [workspace]);

  const editable = workspace && ['OWNER', 'ADMIN'].includes(workspace.role);
  const joinUrl = typeof window !== 'undefined' ? `${window.location.origin}/w/${id}` : `/w/${id}`;
  const qrEndpoint = `${serverInfo.serverUrl.replace(/\/+$/, '')}/api/workspaces/${id}/qr`;

  const handleCopyLink = () => {
    navigator.clipboard.writeText(joinUrl);
    setCopiedLink(true);
    setTimeout(() => setCopiedLink(false), 2000);
  };

  const handleGeneratePassword = () => {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$';
    let generated = '';
    for (let i = 0; i < 8; i++) {
      generated += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    setJoinPassword(generated);
    setShowPass(true);
    setPolicySaved(false);
  };

  const handleSavePolicy = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editable) return;
    setPolicyBusy(true);
    setPolicyError('');
    setPolicySaved(false);
    try {
      await mutate(`/api/workspaces/${id}/join-settings`, 'PATCH', {
        joinPolicy,
        password: joinPassword.trim() || undefined,
      });
      setPolicySaved(true);
      setJoinPassword('');
    } catch (err) {
      setPolicyError(errorMessage(err));
    } finally {
      setPolicyBusy(false);
    }
  };

  return (
    <>
      <Heading
        eyebrow="QUẢN LÝ WORKSPACE"
        title="Cài đặt không gian"
        description="Cập nhật thông tin chung, chính sách mật khẩu gia nhập và quản lý liên kết chia sẻ của nhóm."
      />

      <Feedback loading={loading} error={error} retry={refresh} />

      {workspace && (
        <div className="section-gap settings-panel space-y-6">
          {/* 1. General Info Panel */}
          <Panel title="Thông tin chung" action={<Tag>{workspace.role}</Tag>}>
            <form
              className="panel-body form-stack"
              onSubmit={async event => {
                event.preventDefault();
                setBusy(true);
                setFailure('');
                setSaved(false);
                try {
                  await mutate(`/api/workspaces/${id}`, 'PATCH', {
                    name: name.trim(),
                    description: description.trim() || null,
                  });
                  setSaved(true);
                } catch (err) {
                  setFailure(errorMessage(err));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {mode === 'demo' && (
                <Notice>Cài đặt trong demo là dữ liệu mẫu. Kết nối server để lưu thay đổi thật.</Notice>
              )}
              {failure && <Notice danger>{failure}</Notice>}
              {saved && <Success>Server đã lưu thông tin workspace.</Success>}

              <Field
                label="Tên không gian"
                value={name}
                onChange={event => {
                  setName(event.target.value);
                  setSaved(false);
                }}
                required
                maxLength={100}
                disabled={!editable || mode === 'demo'}
              />

              <label className="field">
                <span>Mô tả</span>
                <textarea
                  rows={4}
                  value={description}
                  onChange={event => {
                    setDescription(event.target.value);
                    setSaved(false);
                  }}
                  maxLength={2000}
                  disabled={!editable || mode === 'demo'}
                />
              </label>

              <div className="form-actions">
                <Action type="submit" busy={busy} disabled={!editable || mode === 'demo' || !name.trim()}>
                  <Save size={15} /> Lưu thay đổi
                </Action>
              </div>
            </form>
          </Panel>

          {/* 2. Join Policy & Passcode Panel */}
          <Panel
            title="Chính sách gia nhập & Mật khẩu nhóm"
            description="Cho phép người khác tự vào nhóm bằng mật khẩu mà không cần quản trị viên gửi lời mời từng người."
            action={
              <Tag tone={joinPolicy === 'PASSWORD_PROTECTED' ? 'blue' : joinPolicy === 'OPEN_LAN' ? 'green' : 'neutral'}>
                {joinPolicy === 'PASSWORD_PROTECTED'
                  ? 'Có mật khẩu'
                  : joinPolicy === 'OPEN_LAN'
                  ? 'Mở tự do LAN'
                  : 'Chỉ lời mời'}
              </Tag>
            }
          >
            <form className="panel-body form-stack" onSubmit={handleSavePolicy}>
              {policyError && <Notice danger>{policyError}</Notice>}
              {policySaved && <Success>Đã cập nhật chính sách gia nhập nhóm thành công!</Success>}

              <div className="space-y-3">
                <span className="text-xs font-semibold text-slate-300 block">Chế độ tham gia</span>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <label
                    className={`p-3.5 rounded-xl border cursor-pointer transition-all flex flex-col justify-between ${
                      joinPolicy === 'INVITE_ONLY'
                        ? 'bg-slate-900 border-cyan-500 shadow-md'
                        : 'bg-slate-950/40 border-slate-800 hover:border-slate-700'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <input
                        type="radio"
                        name="joinPolicy"
                        value="INVITE_ONLY"
                        checked={joinPolicy === 'INVITE_ONLY'}
                        onChange={() => {
                          setJoinPolicy('INVITE_ONLY');
                          setPolicySaved(false);
                        }}
                        disabled={!editable || mode === 'demo'}
                        className="text-cyan-500"
                      />
                      <strong className="text-xs text-slate-200">Chỉ người được mời</strong>
                    </div>
                    <span className="text-[11px] text-slate-400 mt-2">
                      Admin/Owner phải thêm email hoặc username thủ công.
                    </span>
                  </label>

                  <label
                    className={`p-3.5 rounded-xl border cursor-pointer transition-all flex flex-col justify-between ${
                      joinPolicy === 'PASSWORD_PROTECTED'
                        ? 'bg-slate-900 border-cyan-500 shadow-md'
                        : 'bg-slate-950/40 border-slate-800 hover:border-slate-700'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <input
                        type="radio"
                        name="joinPolicy"
                        value="PASSWORD_PROTECTED"
                        checked={joinPolicy === 'PASSWORD_PROTECTED'}
                        onChange={() => {
                          setJoinPolicy('PASSWORD_PROTECTED');
                          setPolicySaved(false);
                        }}
                        disabled={!editable || mode === 'demo'}
                        className="text-cyan-500"
                      />
                      <strong className="text-xs text-slate-200">Bảo vệ bằng mật khẩu</strong>
                    </div>
                    <span className="text-[11px] text-slate-400 mt-2">
                      Người có link/mã nhóm nhập đúng mật khẩu để tự tham gia.
                    </span>
                  </label>

                  <label
                    className={`p-3.5 rounded-xl border cursor-pointer transition-all flex flex-col justify-between ${
                      joinPolicy === 'OPEN_LAN'
                        ? 'bg-slate-900 border-cyan-500 shadow-md'
                        : 'bg-slate-950/40 border-slate-800 hover:border-slate-700'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <input
                        type="radio"
                        name="joinPolicy"
                        value="OPEN_LAN"
                        checked={joinPolicy === 'OPEN_LAN'}
                        onChange={() => {
                          setJoinPolicy('OPEN_LAN');
                          setPolicySaved(false);
                        }}
                        disabled={!editable || mode === 'demo'}
                        className="text-cyan-500"
                      />
                      <strong className="text-xs text-slate-200">Mở tự do trong LAN</strong>
                    </div>
                    <span className="text-[11px] text-slate-400 mt-2">
                      Bất kỳ ai mở liên kết nhóm đều có thể bấm vào ngay.
                    </span>
                  </label>
                </div>
              </div>

              {joinPolicy === 'PASSWORD_PROTECTED' && (
                <div className="p-4 bg-slate-950/80 rounded-xl border border-slate-800 space-y-3">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
                      <KeyRound size={13} className="text-cyan-400" />
                      <span>{workspace.hasPassword ? 'Đổi mật khẩu gia nhập nhóm' : 'Đặt mật khẩu gia nhập nhóm'}</span>
                    </label>
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        onClick={handleGeneratePassword}
                        disabled={!editable || mode === 'demo'}
                        className="text-[11px] text-cyan-400 hover:text-cyan-300 transition-colors font-medium"
                      >
                        Tạo mật khẩu ngẫu nhiên
                      </button>
                      <button
                        type="button"
                        onClick={() => setShowPass(v => !v)}
                        className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center gap-1"
                      >
                        {showPass ? <EyeOff size={12} /> : <Eye size={12} />}
                        <span>{showPass ? 'Ẩn' : 'Hiện'}</span>
                      </button>
                    </div>
                  </div>

                  <input
                    type={showPass ? 'text' : 'password'}
                    value={joinPassword}
                    onChange={e => {
                      setJoinPassword(e.target.value);
                      setPolicySaved(false);
                    }}
                    placeholder={
                      workspace.hasPassword
                        ? 'Để trống nếu muốn giữ nguyên mật khẩu cũ, hoặc nhập mật khẩu mới (4-100 ký tự)...'
                        : 'Nhập mật khẩu mới cho nhóm (4-100 ký tự)...'
                    }
                    disabled={!editable || mode === 'demo'}
                    className="w-full bg-slate-900 border border-slate-800 rounded-xl px-3.5 py-2 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:border-cyan-500 focus:outline-none"
                  />
                  {workspace.hasPassword && !joinPassword && (
                    <span className="text-[11px] text-emerald-400 block">
                      ✓ Nhóm hiện đã có mật khẩu hoạt động.
                    </span>
                  )}
                </div>
              )}

              <div className="form-actions">
                <Action
                  type="submit"
                  busy={policyBusy}
                  disabled={
                    !editable ||
                    mode === 'demo' ||
                    (joinPolicy === 'PASSWORD_PROTECTED' && !workspace.hasPassword && !joinPassword.trim())
                  }
                >
                  <Save size={15} /> Lưu chính sách gia nhập
                </Action>
              </div>
            </form>
          </Panel>

          {/* 3. Link & QR Code Sharing Panel */}
          <Panel
            title="Liên kết tham gia & Mã QR"
            description="Chia sẻ liên kết hoặc mã QR để thành viên quét bằng điện thoại và tham gia nhanh."
          >
            <div className="panel-body space-y-4">
              <div className="p-3.5 bg-slate-950/80 rounded-xl border border-slate-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div className="min-w-0">
                  <span className="text-[11px] text-slate-400 block font-medium">Đường dẫn mời tham gia:</span>
                  <code className="text-xs font-mono text-cyan-300 block truncate mt-0.5 select-all">
                    {joinUrl}
                  </code>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    type="button"
                    onClick={handleCopyLink}
                    className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold transition-colors flex items-center gap-1.5"
                  >
                    {copiedLink ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
                    <span>{copiedLink ? 'Đã sao chép' : 'Sao chép link'}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setShowQr(v => !v)}
                    className="px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-cyan-300 text-xs font-semibold transition-colors flex items-center gap-1.5"
                  >
                    <QrCode size={13} />
                    <span>{showQr ? 'Ẩn QR' : 'Hiện QR'}</span>
                  </button>
                </div>
              </div>

              {showQr && (
                <div className="p-5 bg-slate-950/90 rounded-2xl border border-slate-800 text-center space-y-3 animate-in fade-in duration-150">
                  <strong className="text-xs text-slate-200 block">
                    Quét mã QR để mở trang tham gia trên thiết bị di động:
                  </strong>
                  <div className="bg-white p-3 rounded-xl w-fit mx-auto shadow-xl">
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
                  <span className="text-[11px] text-slate-400 block">
                    Thiết bị cần kết nối cùng mạng Wi-Fi/LAN với máy chủ LANDrop.
                  </span>
                </div>
              )}
            </div>
          </Panel>

          {/* 4. Access Info Panel */}
          <Panel title="Thông tin kỹ thuật">
            <div className="panel-body">
              <dl className="detail-grid">
                <dt>Mã workspace</dt>
                <dd className="font-mono">{workspace.id}</dd>
                <dt>Vai trò của bạn</dt>
                <dd>{workspace.role}</dd>
                <dt>Chính sách gia nhập</dt>
                <dd>{workspace.joinPolicy || 'INVITE_ONLY'}</dd>
                <dt>Ngày tạo</dt>
                <dd>{new Date(workspace.createdAt).toLocaleDateString('vi-VN')}</dd>
              </dl>
            </div>
          </Panel>

          {/* 5. Danger Zone */}
          <Panel
            title={workspace.role === 'OWNER' ? 'Xóa workspace' : 'Rời không gian'}
            description={
              workspace.role === 'OWNER'
                ? 'Ảnh hưởng toàn bộ thành viên và metadata của nhóm.'
                : 'Bạn sẽ mất quyền truy cập các kênh và tài liệu của nhóm.'
            }
          >
            <div className="panel-body">
              <Action
                tone="danger"
                disabled={mode === 'demo'}
                onClick={() => {
                  setDanger(workspace.role === 'OWNER' ? 'delete' : 'leave');
                  setFailure('');
                }}
              >
                {workspace.role === 'OWNER' ? 'Xóa không gian' : 'Rời nhóm'}
              </Action>
            </div>
          </Panel>
        </div>
      )}

      {/* Danger Confirmation Modal */}
      {danger && (
        <Dialog
          title={danger === 'delete' ? 'Xác nhận xóa workspace' : 'Xác nhận rời nhóm'}
          description={
            danger === 'delete'
              ? 'Server sẽ xóa workspace cùng membership, kênh và metadata liên quan. Nội dung file trên thiết bị không bị xóa.'
              : 'Bạn mất quyền truy cập workspace sau khi server xác nhận.'
          }
          onClose={() => !busy && setDanger(null)}
        >
          <div className="dialog-content">
            {failure && <Notice danger>{failure}</Notice>}
            <div className="form-actions">
              <Action tone="secondary" disabled={busy} onClick={() => setDanger(null)}>
                Hủy
              </Action>
              <Action
                tone="danger"
                busy={busy}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await mutate(
                      danger === 'delete' ? `/api/workspaces/${id}` : `/api/workspaces/${id}/leave`,
                      danger === 'delete' ? 'DELETE' : 'POST'
                    );
                    router.push('/workspaces');
                  } catch (err) {
                    setFailure(errorMessage(err));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                Xác nhận {danger === 'delete' ? 'xóa' : 'rời nhóm'}
              </Action>
            </div>
          </div>
        </Dialog>
      )}
    </>
  );
}
