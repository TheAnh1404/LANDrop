'use client';

import React, { useEffect, useState } from 'react';
import {
  Activity,
  ArrowDownUp,
  Check,
  CheckCircle2,
  Copy,
  Info,
  Loader2,
  Play,
  Radio,
  Search,
  Send,
  ShieldCheck,
  UserCheck,
  XCircle,
} from 'lucide-react';
import { useDemoTransfers } from '@/lib/transfer/demo-provider';
import { useSession } from '@/lib/session/session-provider';
import { useWebRTC } from '@/lib/webrtc/webrtc-provider';
import type { WebRTCPeerState } from '@/lib/webrtc/types';
import type { WireMember } from '@/lib/api/models';
import { errorMessage } from '@/lib/api/client';
import { formatBytes } from '@/lib/utils';
import {
  Action,
  Empty,
  Field,
  FileGlyph,
  Heading,
  Notice,
  Panel,
  Stat,
  Tag,
} from '../system/primitives';

type TransferInfo = {
  id: string;
  status: string;
  fileId: string;
  file?: { name: string; size: number; workspaceId: string };
  senderUserId: string;
  receiverUserId: string;
  errorMessage?: string;
  createdAt: string;
};

const labels: Record<string, string> = {
  waiting: 'Đang chờ',
  connecting: 'Đang kết nối',
  transferring: 'Đang truyền',
  verifying: 'Đang kiểm tra',
  completed: 'Hoàn tất',
  cancelled: 'Đã hủy',
  failed: 'Thất bại',
  declined: 'Đã từ chối',
  integrity_failed: 'Kiểm tra thất bại',
};

const peerStateTone: Record<WebRTCPeerState, 'neutral' | 'blue' | 'amber' | 'green' | 'red'> = {
  IDLE: 'neutral',
  REQUESTING: 'amber',
  WAITING_ACCEPT: 'amber',
  SIGNALING: 'blue',
  CONNECTING: 'blue',
  CONNECTED: 'green',
  REJECTED: 'red',
  CANCELLED: 'neutral',
  FAILED: 'red',
  DISCONNECTED: 'neutral',
  TIMEOUT: 'amber',
};

const peerStateLabel: Record<WebRTCPeerState, string> = {
  IDLE: 'Sẵn sàng',
  REQUESTING: 'Đang gửi yêu cầu',
  WAITING_ACCEPT: 'Chờ đối phương chấp nhận',
  SIGNALING: 'Đang trao đổi báo hiệu (Offer/Answer/ICE)',
  CONNECTING: 'Đang kết nối PeerConnection & Data Channel',
  CONNECTED: 'P2P Đã kết nối · Data Channel Mở',
  REJECTED: 'Bị từ chối',
  CANCELLED: 'Đã hủy',
  FAILED: 'Kết nối thất bại',
  DISCONNECTED: 'Đã ngắt kết nối',
  TIMEOUT: 'Hết thời gian chờ',
};

export function TransfersScreen({ id }: { id: string }) {
  const { transfers, startDemo, cancelTransfer } = useDemoTransfers();
  const { mode, request, user } = useSession();
  const {
    state: peerState,
    role: peerRole,
    diagnostics,
    incomingRequest,
    testResult,
    localConnectionId,
    requestP2P,
    acceptIncomingRequest,
    rejectIncomingRequest,
    cancelConnection,
    resetToIdle,
  } = useWebRTC();

  const [tab, setTab] = useState('all');
  const [code, setCode] = useState('');
  const [info, setInfo] = useState<TransferInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');

  // P2P Controls State
  const [targetConnectionId, setTargetConnectionId] = useState('');
  const [targetUserId, setTargetUserId] = useState('');
  const [members, setMembers] = useState<WireMember[]>([]);
  const [loadingMembers, setLoadingMembers] = useState(false);
  const [copiedId, setCopiedId] = useState(false);
  const [showDiagnostics, setShowDiagnostics] = useState(false);
  const [initiatingP2P, setInitiatingP2P] = useState(false);
  const [p2pError, setP2pError] = useState('');

  // Fetch workspace members to facilitate peer selection
  useEffect(() => {
    if (mode === 'demo' || !id) return;
    let active = true;

    async function loadMembers() {
      setLoadingMembers(true);
      try {
        const res = await request<{ data: WireMember[] }>(`/api/workspaces/${id}/members`);
        if (active && Array.isArray(res?.data)) {
          setMembers(res.data);
        }
      } catch {
        // Silently fallback if members API is not available
      } finally {
        if (active) setLoadingMembers(false);
      }
    }

    void loadMembers();
    return () => {
      active = false;
    };
  }, [id, mode, request]);

  const handleCopyConnectionId = async () => {
    if (!localConnectionId) return;
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard) {
        await navigator.clipboard.writeText(localConnectionId);
        setCopiedId(true);
        setTimeout(() => setCopiedId(false), 2000);
      }
    } catch {
      /* clipboard write failed */
    }
  };

  const handleStartP2P = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!targetConnectionId.trim()) return;

    setInitiatingP2P(true);
    setP2pError('');
    try {
      const selectedMember = members.find(m => m.userId === targetUserId);
      await requestP2P({
        workspaceId: id,
        receiverConnectionId: targetConnectionId.trim(),
        targetUserId: targetUserId || undefined,
        targetDeviceName: selectedMember?.user?.displayName || selectedMember?.user?.username || 'Peer Browser',
      });
    } catch (err) {
      setP2pError(errorMessage(err));
    } finally {
      setInitiatingP2P(false);
    }
  };

  const visible = transfers.filter(
    item =>
      (!item.workspaceId || item.workspaceId === id) &&
      (tab === 'all' ||
        (tab === 'active'
          ? ['waiting', 'connecting', 'transferring', 'verifying'].includes(item.status)
          : ['completed', 'cancelled', 'failed', 'declined', 'integrity_failed'].includes(item.status)))
  );

  return (
    <>
      <Heading
        eyebrow="THEO DÕI TRUYỀN TỆP & WEBRTC P2P"
        title="Hoạt động truyền tệp"
        description="Kết nối WebRTC PeerConnection, mở RTCDataChannel và kiểm thử truyền thông trực tiếp giữa hai trình duyệt trong mạng nội bộ."
        action={
          mode === 'demo' && (
            <Action onClick={() => startDemo(id)}>
              <Play size={15} />Chạy mô phỏng
            </Action>
          )
        }
      />

      {/* ========================================================================= */}
      {/* 1. Live WebRTC P2P Testing Section */}
      {/* ========================================================================= */}
      {mode === 'live' && (
        <div className="space-y-6 mb-8">
          {/* Incoming P2P Request Banner */}
          {incomingRequest && (
            <div className="p-4 border border-blue-500/30 bg-blue-500/10 rounded-xl space-y-3 animate-pulse">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <Radio size={18} className="text-blue-400" />
                  <strong className="text-blue-200">
                    Yêu cầu kết nối WebRTC P2P từ: {incomingRequest.senderDeviceName || incomingRequest.senderUserId}
                  </strong>
                </div>
                <Tag tone="blue">YÊU CẦU MỚI</Tag>
              </div>
              <p className="text-sm text-neutral-300">
                Phiên gửi (Connection ID): <code className="bg-black/30 px-1.5 py-0.5 rounded text-xs">{incomingRequest.senderConnectionId}</code> · Tệp kiểm tra: <code>{incomingRequest.fileName}</code> ({formatBytes(incomingRequest.fileSize)})
              </p>
              <div className="flex items-center space-x-3 pt-1">
                <Action
                  tone="primary"
                  onClick={() => acceptIncomingRequest(incomingRequest.transferId)}
                >
                  <Check size={15} /> Chấp nhận & Mở Data Channel
                </Action>
                <Action
                  tone="secondary"
                  onClick={() => rejectIncomingRequest('Từ chối bởi người nhận', incomingRequest.transferId)}
                >
                  <XCircle size={15} /> Từ chối
                </Action>
              </div>
            </div>
          )}

          {/* Active Connection & Verification Panel */}
          <Panel
            title="Kiểm thử kết nối WebRTC Peer-to-Peer"
            description="Tạo kênh dữ liệu RTCDataChannel và kiểm tra phản hồi kỹ thuật (PING/PONG) trực tiếp giữa hai trình duyệt."
          >
            <div className="panel-body space-y-6">
              {/* Local Connection ID Badge */}
              <div className="flex flex-wrap items-center justify-between gap-3 p-3 bg-neutral-900/40 border border-neutral-800 rounded-lg">
                <div className="flex items-center space-x-2 text-sm">
                  <span className="text-neutral-400">ID phiên trình duyệt của bạn:</span>
                  <code className="bg-neutral-800 px-2 py-0.5 rounded text-emerald-400 font-mono font-medium text-xs">
                    {localConnectionId || 'Đang kết nối WebSocket...'}
                  </code>
                </div>
                <Action
                  tone="secondary"
                  disabled={!localConnectionId}
                  onClick={handleCopyConnectionId}
                >
                  {copiedId ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
                  <span>{copiedId ? 'Đã sao chép' : 'Sao chép ID phiên'}</span>
                </Action>
              </div>

              {/* State Machine Status Card */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <Stat
                  label="Trạng thái P2P"
                  value={
                    <Tag tone={peerStateTone[peerState]}>
                      {peerStateLabel[peerState]}
                    </Tag>
                  }
                  detail={
                    peerRole
                      ? `Vai trò: ${peerRole === 'offerer' ? 'Offerer (Người gửi)' : 'Answerer (Người nhận)'}`
                      : 'Chưa có phiên hoạt động'
                  }
                  icon={<Activity size={18} />}
                />
                <Stat
                  label="Kênh dữ liệu (Data Channel)"
                  value={
                    diagnostics.dataChannelState === 'open' ? (
                      <span className="text-emerald-400 flex items-center gap-1 font-semibold">
                        <CheckCircle2 size={16} /> Mở (OPEN)
                      </span>
                    ) : (
                      <span className="text-neutral-400 font-medium">
                        {diagnostics.dataChannelState === 'none' ? 'Chưa mở' : diagnostics.dataChannelState}
                      </span>
                    )
                  }
                  detail="Kênh kỹ thuật: landrop-p2p-test"
                  icon={<Radio size={18} />}
                />
                <Stat
                  label="Kiểm tra Round-trip (Ping/Pong)"
                  value={
                    testResult?.passed ? (
                      <span className="text-emerald-400 font-bold">
                        Đạt · {testResult.roundTripMs} ms
                      </span>
                    ) : (
                      <span className="text-neutral-400 font-medium">
                        {peerState === 'CONNECTED' ? 'Đang gửi PING...' : 'Chờ kết nối'}
                      </span>
                    )
                  }
                  detail={testResult ? 'Xác minh kênh dữ liệu 2 chiều thành công' : 'Chưa có kết quả'}
                  icon={<ShieldCheck size={18} />}
                />
              </div>

              {/* Test Passed Callout */}
              {peerState === 'CONNECTED' && testResult?.passed && (
                <div className="p-4 bg-emerald-950/30 border border-emerald-500/40 rounded-xl flex items-center justify-between">
                  <div className="flex items-center space-x-3">
                    <CheckCircle2 size={24} className="text-emerald-400 shrink-0" />
                    <div>
                      <strong className="text-emerald-300 text-sm block">
                        P2P Connected · Data Channel Open · Round-trip Test Passed
                      </strong>
                      <p className="text-xs text-neutral-300 mt-0.5">
                        Đường truyền dữ liệu ngang hàng browser-to-browser hoạt động thật với độ trễ khứ hồi <strong>{testResult.roundTripMs} ms</strong>.
                      </p>
                    </div>
                  </div>
                  <Action tone="secondary" onClick={resetToIdle}>
                    Hoàn tất kiểm thử
                  </Action>
                </div>
              )}

              {/* Error Callout */}
              {diagnostics.lastError && (
                <Notice danger>
                  <div className="flex items-center justify-between w-full">
                    <span>{diagnostics.lastError}</span>
                    <Action tone="ghost" onClick={resetToIdle}>
                      Đóng
                    </Action>
                  </div>
                </Notice>
              )}

              {/* Sender Connection Form */}
              {peerState === 'IDLE' && (
                <form className="form-stack pt-2" onSubmit={handleStartP2P}>
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Member selection */}
                    <label className="field">
                      <span>Chọn thành viên cùng workspace (nếu có)</span>
                      <select
                        className="w-full bg-neutral-900 border border-neutral-800 rounded px-3 py-2 text-sm text-neutral-200"
                        value={targetUserId}
                        onChange={e => {
                          const uid = e.target.value;
                          setTargetUserId(uid);
                        }}
                      >
                        <option value="">— Tùy chọn hoặc nhập trực tiếp ID phiên bên dưới —</option>
                        {members
                          .filter(m => m.userId !== user?.id)
                          .map(m => (
                            <option key={m.id} value={m.userId}>
                              {m.user?.displayName || m.user?.username} ({m.role})
                            </option>
                          ))}
                      </select>
                    </label>

                    {/* Target Connection ID */}
                    <Field
                      label="ID phiên kết nối của đối phương (receiverConnectionId)"
                      value={targetConnectionId}
                      onChange={e => setTargetConnectionId(e.target.value)}
                      placeholder="Nhập ID phiên (ví dụ do tab/máy kia sao chép)"
                      required
                    />
                  </div>

                  {p2pError && <Notice danger>{p2pError}</Notice>}

                  <div className="form-actions flex items-center justify-between pt-2">
                    <p className="text-xs text-neutral-400">
                      💡 Mẹo: Mở hai tab hoặc hai máy tính trong cùng workspace, sao chép ID phiên từ tab thứ hai rồi dán vào đây.
                    </p>
                    <Action type="submit" busy={initiatingP2P} disabled={!targetConnectionId.trim()}>
                      <Send size={15} /> Gửi yêu cầu P2P (Test Ping/Pong)
                    </Action>
                  </div>
                </form>
              )}

              {/* Active / In-flight Controls */}
              {['REQUESTING', 'WAITING_ACCEPT', 'SIGNALING', 'CONNECTING'].includes(peerState) && (
                <div className="flex items-center justify-between p-4 bg-neutral-900/60 border border-neutral-800 rounded-lg">
                  <div className="flex items-center space-x-3 text-sm">
                    <Loader2 size={18} className="animate-spin text-blue-400" />
                    <span>
                      {peerState === 'WAITING_ACCEPT'
                        ? 'Đang chờ đối phương xác nhận trên màn hình của họ...'
                        : 'Đang tiến hành bắt tay WebRTC qua server gateway...'}
                    </span>
                  </div>
                  <Action tone="danger" onClick={() => cancelConnection('Hủy bởi người gửi')}>
                    Hủy kết nối
                  </Action>
                </div>
              )}

              {/* Terminal States Action */}
              {['REJECTED', 'CANCELLED', 'FAILED', 'TIMEOUT', 'DISCONNECTED'].includes(peerState) && (
                <div className="flex items-center justify-between pt-2">
                  <span className="text-sm text-neutral-400">
                    Phiên kiểm thử đã kết thúc.
                  </span>
                  <Action tone="secondary" onClick={resetToIdle}>
                    Thử lại phiên mới
                  </Action>
                </div>
              )}

              {/* Diagnostics Toggle */}
              <div className="pt-2 border-t border-neutral-800/80">
                <button
                  type="button"
                  className="text-xs text-neutral-400 hover:text-neutral-200 flex items-center gap-1.5 transition-colors"
                  onClick={() => setShowDiagnostics(v => !v)}
                >
                  <Info size={14} />
                  <span>{showDiagnostics ? 'Ẩn thông số chẩn đoán (Diagnostics)' : 'Hiển thị thông số chẩn đoán WebRTC (Diagnostics)'}</span>
                </button>

                {showDiagnostics && (
                  <div className="mt-4 p-4 bg-black/40 border border-neutral-800 rounded-lg space-y-3">
                    <div className="flex items-center justify-between">
                      <strong className="text-xs uppercase tracking-wider text-neutral-400">
                        Bảng chẩn đoán kỹ thuật (Technical Diagnostics)
                      </strong>
                      <span className="text-xs text-neutral-400">
                        Cập nhật: {new Date(diagnostics.lastUpdated).toLocaleTimeString()}
                      </span>
                    </div>

                    <dl className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                      <div>
                        <dt className="text-neutral-400">Transfer ID</dt>
                        <dd className="font-mono text-neutral-200 truncate" title={diagnostics.transferId || '—'}>
                          {diagnostics.transferId || '—'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Target Connection</dt>
                        <dd className="font-mono text-neutral-200 truncate" title={diagnostics.targetConnectionId || '—'}>
                          {diagnostics.targetConnectionId || '—'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Target Peer Name</dt>
                        <dd className="text-neutral-200 truncate">{diagnostics.targetDeviceName || '—'}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Local Role</dt>
                        <dd className="text-neutral-200 font-medium">
                          {diagnostics.role ? diagnostics.role.toUpperCase() : 'NONE'}
                        </dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Signaling State</dt>
                        <dd className="text-neutral-200">{diagnostics.signalingState}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">RTC Signaling</dt>
                        <dd className="text-neutral-200">{diagnostics.rtcSignalingState}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">ICE Connection</dt>
                        <dd className="text-neutral-200">{diagnostics.iceConnectionState}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Peer Connection</dt>
                        <dd className="text-neutral-200">{diagnostics.peerConnectionState}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Data Channel</dt>
                        <dd className="text-neutral-200">{diagnostics.dataChannelState}</dd>
                      </div>
                      <div>
                        <dt className="text-neutral-400">Round-trip Latency</dt>
                        <dd className="text-emerald-400 font-semibold">
                          {diagnostics.roundTripLatencyMs !== null ? `${diagnostics.roundTripLatencyMs} ms` : '—'}
                        </dd>
                      </div>
                      <div className="col-span-2">
                        <dt className="text-neutral-400">Last Error</dt>
                        <dd className="text-rose-400 truncate">{diagnostics.lastError || 'None'}</dd>
                      </div>
                    </dl>

                    <p className="text-[11px] text-neutral-400 italic pt-1 border-t border-neutral-900">
                      🔒 Bảo mật: Thông tin SDP và chứng chỉ kết nối không được hiển thị trên giao diện người dùng.
                    </p>
                  </div>
                )}
              </div>
            </div>
          </Panel>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 2. Demo Mode or Fallback Tra Cứu Yêu Cầu Truyền */}
      {/* ========================================================================= */}
      <Notice>
        {mode === 'demo'
          ? 'Các thanh tiến trình bên dưới là mô phỏng giao diện. Không có nội dung file được truyền giữa thiết bị.'
          : 'Bạn có thể kiểm tra các yêu cầu truyền tệp đã đăng ký trên server bằng mã UUID bên dưới.'}
      </Notice>

      {mode === 'demo' ? (
        <>
          <div className="toolbar">
            <div className="tabs">
              {[
                ['all', 'Tất cả'],
                ['active', 'Đang hoạt động'],
                ['history', 'Lịch sử'],
              ].map(([key, label]) => (
                <button
                  key={key}
                  className={tab === key ? 'tab-active' : ''}
                  onClick={() => setTab(key)}
                >
                  {label}
                </button>
              ))}
            </div>
            <Tag tone="amber">DEMO</Tag>
          </div>
          <Panel>
            {visible.map(transfer => (
              <div className="transfer-card" key={transfer.id}>
                <div className="transfer-head">
                  <FileGlyph name={transfer.fileName} />
                  <div>
                    <strong>{transfer.fileName}</strong>
                    <p>
                      {formatBytes(transfer.fileSize)} · {transfer.sender.userName} → {transfer.receiver.userName}
                    </p>
                  </div>
                  <Tag
                    tone={
                      transfer.status === 'completed'
                        ? 'green'
                        : transfer.status === 'failed'
                        ? 'red'
                        : 'blue'
                    }
                  >
                    {labels[transfer.status]} · mẫu
                  </Tag>
                </div>
                <div className="progress-track">
                  <span
                    style={{
                      width: `${Math.min(100, Math.max(0, transfer.progressPercent))}%`,
                    }}
                  />
                </div>
                <div className="transfer-footer">
                  <span>{Math.round(transfer.progressPercent)}% · Tiến trình mô phỏng</span>
                  {['waiting', 'connecting', 'transferring', 'verifying'].includes(transfer.status) && (
                    <Action tone="ghost" onClick={() => cancelTransfer(transfer.id)}>
                      Hủy mô phỏng
                    </Action>
                  )}
                </div>
              </div>
            ))}
            {!visible.length && (
              <Empty
                icon={<ArrowDownUp size={24} />}
                title="Chưa có phiên mô phỏng phù hợp"
                action={
                  <Action tone="secondary" onClick={() => startDemo(id)}>
                    Chạy một phiên mẫu
                  </Action>
                }
              />
            )}
          </Panel>
        </>
      ) : (
        <Panel title="Tra cứu yêu cầu truyền" description="Server kiểm tra quyền của bạn trước khi trả dữ liệu">
          <div className="panel-body">
            <form
              className="form-stack"
              onSubmit={async event => {
                event.preventDefault();
                setBusy(true);
                setFailure('');
                setInfo(null);
                try {
                  const response = await request<{ data: TransferInfo }>(
                    `/api/transfers/${encodeURIComponent(code.trim())}`
                  );
                  if (response.data.file?.workspaceId !== id) {
                    throw new Error('Yêu cầu không thuộc workspace hiện tại hoặc thiếu thông tin workspace.');
                  }
                  setInfo(response.data);
                } catch (error) {
                  setFailure(errorMessage(error));
                } finally {
                  setBusy(false);
                }
              }}
            >
              <Field
                label="Mã transfer (UUID)"
                value={code}
                onChange={event => setCode(event.target.value)}
                required
                pattern="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                placeholder="Mã yêu cầu từ thiết bị hoặc server"
              />
              <div className="form-actions">
                <Action type="submit" busy={busy}>
                  <Search size={15} />Tra cứu
                </Action>
              </div>
            </form>
            {failure && (
              <div className="mt-5">
                <Notice danger>{failure}</Notice>
              </div>
            )}
            {info && (
              <div className="mt-6">
                <dl className="detail-grid">
                  <dt>Tài liệu</dt>
                  <dd>{info.file?.name || info.fileId}</dd>
                  <dt>Dung lượng</dt>
                  <dd>{info.file ? formatBytes(info.file.size) : '—'}</dd>
                  <dt>Trạng thái server</dt>
                  <dd>
                    <Tag tone={info.status === 'COMPLETED' ? 'green' : 'blue'}>{info.status}</Tag>
                  </dd>
                  <dt>Người gửi</dt>
                  <dd>{info.senderUserId}</dd>
                  <dt>Người nhận</dt>
                  <dd>{info.receiverUserId}</dd>
                  <dt>Đăng ký lúc</dt>
                  <dd>{new Date(info.createdAt).toLocaleString('vi-VN')}</dd>
                  {info.errorMessage && (
                    <>
                      <dt>Lỗi</dt>
                      <dd>{info.errorMessage}</dd>
                    </>
                  )}
                </dl>
                {['PENDING', 'ACCEPTED', 'TRANSFERRING'].includes(info.status) &&
                  (user?.id === info.senderUserId || user?.id === info.receiverUserId) && (
                    <div className="form-actions">
                      <Action
                        tone="danger"
                        busy={busy}
                        onClick={async () => {
                          setBusy(true);
                          try {
                            const response = await request<{ data: TransferInfo }>(
                              `/api/transfers/${info.id}/cancel`,
                              {
                                method: 'POST',
                                body: JSON.stringify({ reason: 'Cancelled from browser' }),
                              }
                            );
                            setInfo(response.data);
                          } catch (error) {
                            setFailure(errorMessage(error));
                          } finally {
                            setBusy(false);
                          }
                        }}
                      >
                        Hủy yêu cầu
                      </Action>
                    </div>
                  )}
              </div>
            )}
          </div>
        </Panel>
      )}
    </>
  );
}
