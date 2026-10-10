'use client';

import React, { useEffect, useRef, useState } from 'react';
import {
  Activity,
  ArrowDownUp,
  ArrowLeft,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  Download,
  FileText,
  Globe,
  Image,
  Info,
  Laptop,
  Loader2,
  MessageSquare,
  Monitor,
  Paperclip,
  Play,
  QrCode,
  Radio,
  RefreshCw,
  Search,
  Send,
  Share2,
  ShieldCheck,
  Smartphone,
  Trash2,
  UploadCloud,
  Wifi,
  X,
  XCircle,
  Zap,
} from 'lucide-react';
import { useDemoTransfers } from '@/lib/transfer/demo-provider';
import { useSession } from '@/lib/session/session-provider';
import { useWebRTC } from '@/lib/webrtc/webrtc-provider';
import { ReliableTransferPanel } from './reliable-transfer-panel';
import type { OnlinePeerItem, WebRTCPeerState } from '@/lib/webrtc/types';
import type { WireMember } from '@/lib/api/models';
import { errorMessage } from '@/lib/api/client';
import { formatBytes, formatETA, formatRelativeTime, formatSpeed } from '@/lib/utils';
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
  IDLE: 'Sẵn sàng kết nối',
  REQUESTING: 'Đang gửi yêu cầu...',
  WAITING_ACCEPT: 'Chờ thiết bị đối phương đồng ý...',
  SIGNALING: 'Đang trao đổi báo hiệu WebRTC...',
  CONNECTING: 'Đang thiết lập kênh trực tiếp RTCDataChannel...',
  CONNECTED: 'P2P Đã kết nối · Kênh dữ liệu mở',
  REJECTED: 'Yêu cầu bị từ chối',
  CANCELLED: 'Đã hủy kết nối',
  FAILED: 'Kết nối P2P thất bại',
  DISCONNECTED: 'Đã ngắt kết nối',
  TIMEOUT: 'Hết thời gian chờ kết nối',
};

function getPlatformIcon(platform?: string) {
  const p = (platform || '').toLowerCase();
  if (p.includes('win')) return <Monitor size={18} className="text-cyan-400" />;
  if (p.includes('mac') || p.includes('darwin') || p.includes('ios') || p.includes('apple') || p.includes('iphone') || p.includes('ipad')) {
    return <Smartphone size={18} className="text-purple-400" />;
  }
  if (p.includes('android')) return <Smartphone size={18} className="text-emerald-400" />;
  if (p.includes('linux')) return <Monitor size={18} className="text-amber-400" />;
  return <Globe size={18} className="text-blue-400" />;
}

function playWebNotificationSound(type: 'message' | 'file') {
  if (typeof window === 'undefined') return;
  try {
    const AudioContextClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextClass) return;
    const ctx = new AudioContextClass();
    if (ctx.state === 'suspended') {
      ctx.resume().catch(() => {});
    }
    const osc = ctx.createOscillator();
    osc.onended = () => { void ctx.close().catch(() => {}); };
    const gain = ctx.createGain();
    osc.connect(gain);
    gain.connect(ctx.destination);

    if (type === 'message') {
      osc.type = 'sine';
      osc.frequency.setValueAtTime(587.33, ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(880, ctx.currentTime + 0.12);
      gain.gain.setValueAtTime(0.08, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.22);
      osc.start(ctx.currentTime);
      osc.stop(ctx.currentTime + 0.22);
    } else {
      osc.type = 'triangle';
      osc.frequency.setValueAtTime(523.25, ctx.currentTime);
      osc.frequency.setValueAtTime(659.25, ctx.currentTime + 0.08);
      osc.frequency.setValueAtTime(783.99, ctx.currentTime + 0.16);
      gain.gain.setValueAtTime(0.09, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.35);
      osc.start(ctx.currentTime);
      osc.stop(ctx.currentTime + 0.35);
    }
  } catch {
    /* AudioContext may be blocked until user interaction */
  }
}

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
    transferProgress,
    receivedFiles,
    quickMessages,
    chatMessages,
    peerTyping,
    onlinePeers,
    requestP2P,
    acceptIncomingRequest,
    rejectIncomingRequest,
    cancelConnection,
    resetToIdle,
    sendFile,
    sendQuickText,
    sendChatMessage,
    sendChatTyping,
    clearChatMessages,
    fetchOnlinePeers,
    clearReceivedFiles,
    clearQuickMessages,
    retryMessage,
    cancelMessage,
  } = useWebRTC();

  // Navigation tab: 'chat' (Dual-Pane Web App), 'send' (File Drop), 'text' (Quick Text), 'received' (Inbox), 'lookup' (UUID Audit)
  const [activeTab, setActiveTab] = useState<'chat' | 'send' | 'text' | 'received' | 'lookup'>('chat');
  const [demoFilterTab, setDemoFilterTab] = useState('all');

  // Peer & Search state
  const [selectedPeer, setSelectedPeer] = useState<OnlinePeerItem | null>(null);
  const [peerSearchQuery, setPeerSearchQuery] = useState('');
  const [manualConnectionId, setManualConnectionId] = useState('');
  const [showManualInput, setShowManualInput] = useState(false);
  const [showQrModal, setShowQrModal] = useState(false);
  const [showMobilePeerList, setShowMobilePeerList] = useState(false);

  // File Drop state
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [transferError, setTransferError] = useState('');
  const [isSending, setIsSending] = useState(false);

  // Chat state
  const [chatInput, setChatInput] = useState('');
  const [isChatDragging, setIsChatDragging] = useState(false);
  const chatBottomRef = useRef<HTMLDivElement>(null);
  const chatAttachmentInputRef = useRef<HTMLInputElement>(null);
  const chatMediaInputRef = useRef<HTMLInputElement>(null);
  const typingTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const prevChatCountRef = useRef(chatMessages.length);
  const prevReceivedCountRef = useRef(receivedFiles.length);

  // Quick Text state
  const [quickTextInput, setQuickTextInput] = useState('');
  const [copiedTextId, setCopiedTextId] = useState<string | null>(null);

  // General state
  const [copiedMyId, setCopiedMyId] = useState(false);
  const [isScanning, setIsScanning] = useState(false);
  const [showDiagnostics, setShowDiagnostics] = useState(false);

  // UUID Audit state (legacy lookup)
  const [lookupCode, setLookupCode] = useState('');
  const [lookupInfo, setLookupInfo] = useState<TransferInfo | null>(null);
  const [lookupBusy, setLookupBusy] = useState(false);
  const [lookupFailure, setLookupFailure] = useState('');

  const fileInputRef = useRef<HTMLInputElement>(null);
  const pendingFileToSendRef = useRef<File | null>(null);
  const pendingChatToSendRef = useRef<string | null>(null);

  useEffect(() => {
    if (mode !== 'live' || !diagnostics.targetConnectionId) return;
    setManualConnectionId(diagnostics.targetConnectionId);
    setSelectedPeer(onlinePeers.find(peer => peer.connectionId === diagnostics.targetConnectionId) || {
      connectionId: diagnostics.targetConnectionId, userId: diagnostics.targetUserId,
      displayName: diagnostics.targetDeviceName || 'Thiết bị LAN', deviceName: diagnostics.targetDeviceName || 'Thiết bị LAN', platform: 'browser',
    });
    setShowMobilePeerList(false);
  }, [mode, diagnostics.targetConnectionId]);

  // Periodic peer scan in live mode
  useEffect(() => {
    if (mode !== 'live') return;
    fetchOnlinePeers();
    const interval = setInterval(() => {
      fetchOnlinePeers();
    }, 6000);
    return () => clearInterval(interval);
  }, [mode, fetchOnlinePeers]);

  // When connection succeeds and a file was queued, automatically trigger sendFile
  useEffect(() => {
    if (peerState === 'CONNECTED' && pendingFileToSendRef.current) {
      const fileToSend = pendingFileToSendRef.current;
      pendingFileToSendRef.current = null;
      setIsSending(true);
      sendFile(fileToSend)
        .catch(err => setTransferError(errorMessage(err)))
        .finally(() => setIsSending(false));
    }
  }, [peerState, sendFile]);

  // When connection succeeds and a chat message was queued, automatically trigger sendChatMessage
  useEffect(() => {
    if (peerState === 'CONNECTED' && pendingChatToSendRef.current) {
      const msg = pendingChatToSendRef.current;
      pendingChatToSendRef.current = null;
      try {
        void sendChatMessage(msg).catch(err => setTransferError(errorMessage(err)));
      } catch (err) {
        setTransferError(errorMessage(err));
      }
    }
  }, [peerState, sendChatMessage]);

  // Handle Scanning Refresh
  const handleScanNow = () => {
    setIsScanning(true);
    fetchOnlinePeers();
    setTimeout(() => setIsScanning(false), 800);
  };

  // Audio chime when receiving messages
  useEffect(() => {
    if (chatMessages.length > prevChatCountRef.current) {
      const latest = chatMessages[chatMessages.length - 1];
      if (latest && latest.direction === 'received') {
        playWebNotificationSound(latest.type === 'file' ? 'file' : 'message');
      }
    }
    prevChatCountRef.current = chatMessages.length;
  }, [chatMessages]);

  // Audio chime when file is received
  useEffect(() => {
    if (receivedFiles.length > prevReceivedCountRef.current) {
      playWebNotificationSound('file');
    }
    prevReceivedCountRef.current = receivedFiles.length;
  }, [receivedFiles]);

  // Auto scroll chat to bottom
  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages.length, peerTyping]);

  // Handle Chat Input & Typing Indicator Broadcast
  const handleChatInputChange = (val: string) => {
    setChatInput(val);
    if (peerState === 'CONNECTED') {
      sendChatTyping(true);
      if (typingTimeoutRef.current) clearTimeout(typingTimeoutRef.current);
      typingTimeoutRef.current = setTimeout(() => {
        sendChatTyping(false);
      }, 2000);
    }
  };

  // Send Chat Message Action
  const handleSendChat = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    const text = chatInput.trim();
    if (!text) return;

    if (peerState !== 'CONNECTED' || diagnostics.targetConnectionId !== (selectedPeer?.connectionId || manualConnectionId.trim())) {
      const targetConnId = selectedPeer?.connectionId || manualConnectionId.trim();
      if (!targetConnId) {
        setTransferError('Vui lòng chọn thiết bị nhận từ Radar trước khi gửi tin nhắn.');
        return;
      }
      if (!await handleConnectPeer()) return;
    }

    try {
      if (typingTimeoutRef.current) {
        clearTimeout(typingTimeoutRef.current);
        sendChatTyping(false);
      }
      await sendChatMessage(text);
      setChatInput('');
      setTransferError('');
    } catch (err) {
      setTransferError(errorMessage(err));
    }
  };

  // Direct File Transfer helper
  const handleSendFileDirect = async (file: File) => {
    if (!file) return;
    const targetConnId = selectedPeer?.connectionId || manualConnectionId.trim();
    if (!targetConnId) {
      setTransferError('Vui lòng chọn thiết bị nhận từ Radar trước khi gửi tệp.');
      return;
    }
    setTransferError('');
    setIsSending(true);
    try {
      if (peerState === 'CONNECTED' && diagnostics.targetConnectionId === targetConnId) {
        await sendFile(file);
      } else {
        await requestP2P({
          workspaceId: id,
          receiverConnectionId: targetConnId,
          targetDeviceName: selectedPeer?.deviceName || 'Thiết bị đối phương',
          fileName: file.name,
          fileSize: file.size,
        });
        await sendFile(file);
      }
    } catch (err) {
      pendingFileToSendRef.current = null;
      setTransferError(errorMessage(err));
    } finally {
      setIsSending(false);
    }
  };

  // Drag & drop on chat area
  const handleChatDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsChatDragging(true);
  };
  const handleChatDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsChatDragging(false);
  };
  const handleChatDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    setIsChatDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      for (const file of Array.from(e.dataTransfer.files)) await handleSendFileDirect(file);
    }
  };

  // Manual connect trigger
  const handleConnectPeer = async () => {
    const targetConnId = selectedPeer?.connectionId || manualConnectionId.trim();
    if (!targetConnId) return false;
    try {
      setTransferError('');
      await requestP2P({
        workspaceId: id,
        receiverConnectionId: targetConnId,
        targetDeviceName: selectedPeer?.deviceName || 'Thiết bị đối phương',
      });
      return true;
    } catch (err) {
      setTransferError(errorMessage(err));
      return false;
    }
  };

  // Copy local ID
  const handleCopyMyId = async () => {
    if (!localConnectionId) return;
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard) {
        await navigator.clipboard.writeText(localConnectionId);
        setCopiedMyId(true);
        setTimeout(() => setCopiedMyId(false), 2000);
      }
    } catch { /* clipboard fallback */ }
  };

  // Copy Quick Text message
  const handleCopyText = async (id: string, text: string) => {
    try {
      if (typeof navigator !== 'undefined' && navigator.clipboard) {
        await navigator.clipboard.writeText(text);
        setCopiedTextId(id);
        setTimeout(() => setCopiedTextId(null), 2000);
      }
    } catch { /* clipboard fallback */ }
  };

  // Select target peer
  const handleSelectPeer = (peer: OnlinePeerItem) => {
    if (diagnostics.targetConnectionId && diagnostics.targetConnectionId !== peer.connectionId) {
      pendingFileToSendRef.current = null;
      pendingChatToSendRef.current = null;
      setChatInput('');
    }
    setSelectedPeer(peer);
    setManualConnectionId(peer.connectionId);
    setShowMobilePeerList(false);
    setTransferError('');
    {
      void requestP2P({
        workspaceId: id,
        receiverConnectionId: peer.connectionId,
        targetDeviceName: peer.deviceName || 'Thiết bị đối phương',
      }).catch(err => setTransferError(errorMessage(err)));
    }
  };

  const handleManualConnectionChange = (value: string) => {
    if (diagnostics.targetConnectionId && diagnostics.targetConnectionId !== value.trim()) {
      pendingFileToSendRef.current = null;
      setChatInput('');
    }
    setManualConnectionId(value);
    setSelectedPeer(null);
  };

  // File Drag & Drop Handlers
  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };
  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  };
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      setSelectedFile(e.dataTransfer.files[0]);
      setTransferError('');
    }
  };
  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      setSelectedFile(e.target.files[0]);
      setTransferError('');
    }
  };

  // Send File Action
  const handleInitiateTransfer = async () => {
    if (!selectedFile) {
      setTransferError('Vui lòng chọn tệp tin cần gửi.');
      return;
    }
    const targetConnId = manualConnectionId.trim() || selectedPeer?.connectionId;
    if (!targetConnId) {
      setTransferError('Vui lòng chọn một thiết bị online từ Radar hoặc nhập ID kết nối.');
      return;
    }

    setTransferError('');
    setIsSending(true);

    try {
      if (peerState === 'CONNECTED') {
        // Already connected to peer: send file directly over RTCDataChannel!
        await sendFile(selectedFile);
      } else {
        // Need to initiate P2P handshake first: queue file to auto-send on open
        await requestP2P({
          workspaceId: id,
          receiverConnectionId: targetConnId,
          targetDeviceName: selectedPeer?.deviceName || 'Thiết bị đối phương',
          fileName: selectedFile.name,
          fileSize: selectedFile.size,
        });
        await sendFile(selectedFile);
      }
    } catch (err) {
      pendingFileToSendRef.current = null;
      setTransferError(errorMessage(err));
    } finally {
      setIsSending(false);
    }
  };

  // Quick Text Send Action
  const handleSendQuickText = (e: React.FormEvent) => {
    e.preventDefault();
    if (!quickTextInput.trim()) return;

    if (peerState !== 'CONNECTED') {
      setTransferError('Kênh WebRTC chưa kết nối. Vui lòng chọn thiết bị và kết nối trước khi gửi văn bản.');
      return;
    }

    try {
      sendQuickText(quickTextInput.trim());
      setQuickTextInput('');
    } catch (err) {
      setTransferError(errorMessage(err));
    }
  };

  // Visible Demo Transfers
  const demoVisible = transfers.filter(
    item =>
      (!item.workspaceId || item.workspaceId === id) &&
      (demoFilterTab === 'all' ||
        (demoFilterTab === 'active'
          ? ['waiting', 'connecting', 'transferring', 'verifying'].includes(item.status)
          : ['completed', 'cancelled', 'failed', 'declined', 'integrity_failed'].includes(item.status)))
  );

  return (
    <div className="p2p-screen space-y-6 max-w-7xl mx-auto rounded-3xl bg-slate-950 p-4 sm:p-6 pb-8 text-slate-100 shadow-xl">
      {/* ========================================================================= */}
      {/* 1. Header & Quick Status Strip */}
      {/* ========================================================================= */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-800/80 pb-5">
        <div>
          <div className="flex items-center gap-2 text-xs font-semibold tracking-wider text-cyan-400 uppercase">
            <Radio size={14} className="animate-pulse" />
            <span>WebRTC LAN High-Speed Engine</span>
          </div>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-slate-100 tracking-tight mt-1">
            Chat LAN. Tệp trọn vẹn.
          </h1>
          <p className="text-sm text-slate-400 mt-1">
            Chat trực tiếp và chuyển tệp trong LAN, có xác minh nội dung và tiếp tục khi gián đoạn.
          </p>
        </div>

        {/* Local Session Badge */}
        <div className="flex flex-wrap items-center gap-2.5 bg-slate-900/90 border border-slate-800 rounded-xl p-2.5 backdrop-blur-md">
          <div className="flex items-center gap-2 text-xs">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-ping inline-block" />
            <span className="text-slate-400">ID của bạn:</span>
            <code className="bg-slate-950/80 px-2 py-0.5 rounded font-mono text-cyan-300 font-medium text-xs border border-slate-800">
              {localConnectionId ? localConnectionId.substring(0, 16) + '...' : 'Đang kết nối...'}
            </code>
          </div>
          <button
            type="button"
            onClick={handleCopyMyId}
            disabled={!localConnectionId}
            className="flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors font-medium"
          >
            {copiedMyId ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
            <span>{copiedMyId ? 'Đã chép' : 'Sao chép ID'}</span>
          </button>
        </div>
      </div>

      {/* ========================================================================= */}
      {/* 2. Incoming Transfer Request Banner (Instant Glass Banner) */}
      {/* ========================================================================= */}
      {mode === 'live' && <ReliableTransferPanel canSend={!!(selectedPeer?.connectionId || manualConnectionId.trim())} onFiles={async files => { for (const file of files) await handleSendFileDirect(file); }} />}
      {incomingRequest && (
        <div className="p-4 sm:p-5 border border-cyan-500/40 bg-gradient-to-r from-cyan-950/40 to-slate-900/60 backdrop-blur-lg rounded-2xl shadow-xl space-y-3 animate-in fade-in slide-in-from-top-4 duration-300">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-3">
              <span className="p-2 bg-cyan-500/20 text-cyan-400 rounded-xl">
                <Radio size={20} className="animate-spin" />
              </span>
              <div>
                <strong className="text-base text-cyan-200 block">
                  Yêu cầu truyền tệp từ: {incomingRequest.senderDeviceName || 'Thiết bị trong mạng'}
                </strong>
                <p className="text-xs text-slate-400 mt-0.5">
                  ID gửi: <code className="text-slate-300">{incomingRequest.senderConnectionId}</code>
                </p>
              </div>
            </div>
            <Tag tone="blue">YÊU CẦU MỚI</Tag>
          </div>

          <div className="bg-slate-950/50 p-3 rounded-xl border border-slate-800/80 flex items-center justify-between text-sm">
            <div className="flex items-center gap-2">
              <FileGlyph name={incomingRequest.fileName} />
              <div>
                <span className="font-semibold text-slate-200">{incomingRequest.fileName}</span>
                <span className="text-xs text-slate-400 block">{formatBytes(incomingRequest.fileSize)}</span>
              </div>
            </div>
            <span className="text-xs text-slate-400">WebRTC DataChannel P2P</span>
          </div>

          <div className="flex items-center justify-end space-x-3 pt-1">
            <button
              type="button"
              onClick={() => rejectIncomingRequest('Từ chối bởi người nhận', incomingRequest.transferId)}
              className="px-4 py-2 text-xs font-semibold rounded-xl bg-slate-800 hover:bg-slate-700 text-rose-300 transition-colors flex items-center gap-1.5"
            >
              <XCircle size={15} /> Từ chối
            </button>
            <button
              type="button"
              onClick={() => acceptIncomingRequest(incomingRequest.transferId)}
              className="px-5 py-2 text-xs font-semibold rounded-xl bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 font-bold shadow-lg shadow-cyan-500/20 transition-all flex items-center gap-1.5"
            >
              <Check size={16} /> Chấp nhận & Nhận tệp
            </button>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 3. Live Active Transfer Speedometer & Progress Card */}
      {/* ========================================================================= */}
      {transferProgress && (
        <div className="p-5 sm:p-6 bg-gradient-to-br from-slate-900/90 via-slate-900/80 to-slate-950 border border-cyan-500/30 rounded-2xl shadow-[0_0_30px_rgba(6,182,212,0.12)] space-y-4 backdrop-blur-md">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <span className={`p-2 rounded-xl ${transferProgress.direction === 'sending' ? 'bg-cyan-500/20 text-cyan-400' : 'bg-emerald-500/20 text-emerald-400'}`}>
                {transferProgress.direction === 'sending' ? <UploadCloud size={20} /> : <Download size={20} />}
              </span>
              <div>
                <strong className="text-slate-100 text-base font-semibold block truncate max-w-xs sm:max-w-md">
                  {transferProgress.fileName}
                </strong>
                <span className="text-xs text-slate-400">
                  {transferProgress.direction === 'sending' ? 'Đang gửi tới đối phương' : 'Đang nhận từ đối phương'} · {formatBytes(transferProgress.bytesTransferred)} / {formatBytes(transferProgress.fileSize)}
                </span>
              </div>
            </div>

            {/* Speed Gauge Callout */}
            <div className="text-right">
              <span className="text-xl sm:text-2xl font-black font-mono tracking-tight text-cyan-400 block">
                {formatSpeed(transferProgress.speedBytesPerSec)}
              </span>
              <span className="text-[11px] text-slate-400 font-medium">
                {transferProgress.status === 'completed'
                  ? 'Hoàn tất 100%'
                  : transferProgress.etaSeconds > 0
                  ? `Còn ~${formatETA(transferProgress.etaSeconds)}`
                  : 'Đang tính toán tốc độ...'}
              </span>
            </div>
          </div>

          {/* Glowing Animated Progress Track */}
          <div className="space-y-1.5">
            <div className="h-3 w-full bg-slate-800/80 rounded-full overflow-hidden p-0.5 border border-slate-700/50">
              <div
                className={`h-full rounded-full transition-all duration-200 ${
                  transferProgress.status === 'completed'
                    ? 'bg-gradient-to-r from-emerald-500 to-teal-400 shadow-[0_0_12px_rgba(16,185,129,0.5)]'
                    : 'bg-gradient-to-r from-cyan-500 via-teal-400 to-emerald-400 shadow-[0_0_12px_rgba(6,182,212,0.5)]'
                }`}
                style={{ width: `${Math.min(100, Math.max(1, transferProgress.percent))}%` }}
              />
            </div>
            <div className="flex justify-between items-center text-xs text-slate-400 px-1">
              <span className="font-mono">{transferProgress.percent}%</span>
              {transferProgress.status === 'transferring' && (
                <button
                  type="button"
                  onClick={() => cancelConnection('Hủy bởi người dùng')}
                  className="text-rose-400 hover:text-rose-300 font-medium transition-colors"
                >
                  Hủy truyền
                </button>
              )}
              {transferProgress.status === 'completed' && (
                <span className="text-emerald-400 font-medium flex items-center gap-1">
                  <CheckCircle2 size={13} /> Truyền thành công!
                </span>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 4. Navigation Tabs */}
      {/* ========================================================================= */}
      <div className="flex min-w-0 items-center justify-between border-b border-slate-800 pb-2 overflow-x-auto">
        <div className="flex shrink-0 items-center gap-2 p-1 bg-slate-900/70 border border-slate-800/80 rounded-xl whitespace-nowrap [&_button]:shrink-0">
          <button
            type="button"
            onClick={() => setActiveTab('chat')}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all ${
              activeTab === 'chat'
                ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <MessageSquare size={14} className={activeTab === 'chat' ? 'text-cyan-400' : ''} />
            <span>Hội thoại & P2P Web App</span>
            {chatMessages.length > 0 && (
              <span className="px-1.5 py-0.2 bg-cyan-500/20 text-[10px] rounded-full text-cyan-300 font-bold">
                {chatMessages.length}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('send')}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all ${
              activeTab === 'send'
                ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Zap size={14} className={activeTab === 'send' ? 'text-cyan-400' : ''} />
            <span>Gửi tệp siêu tốc (P2P)</span>
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('text')}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all ${
              activeTab === 'text'
                ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <FileText size={14} className={activeTab === 'text' ? 'text-cyan-400' : ''} />
            <span>Bản ghi nhớ / Văn bản</span>
            {quickMessages.length > 0 && (
              <span className="px-1.5 py-0.2 bg-slate-800 text-[10px] rounded-full text-slate-300">
                {quickMessages.length}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('received')}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all ${
              activeTab === 'received'
                ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Download size={14} className={activeTab === 'received' ? 'text-cyan-400' : ''} />
            <span>Tệp đã nhận</span>
            {receivedFiles.length > 0 && (
              <span className="px-1.5 py-0.2 bg-emerald-500/20 text-[10px] rounded-full text-emerald-300 font-bold">
                {receivedFiles.length}
              </span>
            )}
          </button>

          <button
            type="button"
            onClick={() => setActiveTab('lookup')}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all ${
              activeTab === 'lookup'
                ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/20 text-cyan-300 border border-cyan-500/30 shadow-sm'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Search size={14} className={activeTab === 'lookup' ? 'text-cyan-400' : ''} />
            <span>Tra cứu máy chủ</span>
          </button>
        </div>

        {/* Live / Demo Mode Badge */}
        <div className="hidden sm:flex items-center gap-2 text-xs">
          {mode === 'demo' ? (
            <Tag tone="amber">CHẾ ĐỘ MÔ PHỎNG</Tag>
          ) : (
            <span className="flex items-center gap-1.5 text-xs text-slate-400">
              <Activity size={13} className="text-emerald-400" />
              <span>Cổng Realtime đang bật</span>
            </span>
          )}
        </div>
      </div>

      {/* ========================================================================= */}
      {/* 5. TAB: P2P WEB APP & REALTIME CONVERSATION (MASTER-DETAIL DUAL PANE)     */}
      {/* ========================================================================= */}
      {activeTab === 'chat' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
          {/* --------------------------------------------------------------------- */}
          {/* LEFT PANE: LAN Device Radar & Session Directory (4 cols)              */}
          {/* --------------------------------------------------------------------- */}
          <div className={`lg:col-span-5 xl:col-span-4 space-y-4 ${selectedPeer && !showMobilePeerList ? 'hidden lg:block' : 'block'}`}>
            <div className="p-4 sm:p-5 bg-slate-900/80 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-3.5 shadow-xl">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Wifi size={18} className="text-cyan-400" />
                    <span className="absolute -top-1 -right-1 w-2 h-2 rounded-full bg-cyan-400 animate-ping" />
                  </div>
                  <div>
                    <strong className="text-sm font-bold text-slate-100 block">
                      Thiết bị LAN lân cận
                    </strong>
                    <span className="text-[11px] text-slate-400">
                      {onlinePeers.length} máy đang phát hiện qua WebRTC
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() => setShowQrModal(true)}
                    title="Mã QR & Link chia sẻ"
                    className="p-2 rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-300 hover:text-cyan-300 transition-colors"
                  >
                    <QrCode size={15} />
                  </button>
                  <button
                    type="button"
                    onClick={handleScanNow}
                    title="Quét lại mạng"
                    className="flex items-center gap-1 px-2.5 py-1.5 text-xs rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition-colors"
                  >
                    <RefreshCw size={12} className={isScanning ? 'animate-spin text-cyan-400' : ''} />
                    <span className="hidden sm:inline">Quét lại</span>
                  </button>
                </div>
              </div>

              {/* Peer Search Filter */}
              <div className="relative">
                <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  value={peerSearchQuery}
                  onChange={e => setPeerSearchQuery(e.target.value)}
                  placeholder="Lọc thiết bị theo tên hoặc HĐH..."
                  className="w-full bg-slate-950/70 border border-slate-800/80 rounded-xl pl-8 pr-3 py-2 text-xs text-slate-200 placeholder-slate-500 focus:border-cyan-500 focus:outline-none"
                />
              </div>

              {/* Peer Device List */}
              {(() => {
                const filteredPeers = onlinePeers.filter(p => {
                  const query = peerSearchQuery.toLowerCase().trim();
                  if (!query) return true;
                  return (
                    (p.deviceName || '').toLowerCase().includes(query) ||
                    (p.displayName || '').toLowerCase().includes(query) ||
                    (p.platform || '').toLowerCase().includes(query) ||
                    p.connectionId.toLowerCase().includes(query)
                  );
                });

                if (filteredPeers.length === 0) {
                  return (
                    <div className="p-6 border border-dashed border-slate-800 rounded-xl text-center space-y-2.5 bg-slate-950/40">
                      <Radio size={28} className="text-slate-400 animate-pulse mx-auto" />
                      <strong className="text-xs font-semibold text-slate-300 block">
                        {onlinePeers.length === 0 ? 'Đang tìm kiếm thiết bị LAN...' : 'Không tìm thấy thiết bị phù hợp'}
                      </strong>
                      <p className="text-[11px] text-slate-400 max-w-xs mx-auto">
                        Mở LANDrop trên một thiết bị khác cùng mạng Wi-Fi/LAN để thấy nhau ngay lập tức.
                      </p>
                    </div>
                  );
                }

                return (
                  <div className="space-y-2 max-h-[460px] overflow-y-auto pr-1">
                    {filteredPeers.map(peer => {
                      const isSelected = selectedPeer?.connectionId === peer.connectionId;
                      return (
                        <div
                          key={peer.connectionId}
                          onClick={() => handleSelectPeer(peer)}
                          className={`p-3 rounded-xl border transition-all cursor-pointer flex items-center justify-between ${
                            isSelected
                              ? 'bg-gradient-to-r from-cyan-950/60 to-slate-900 border-cyan-500/80 shadow-[0_0_15px_rgba(6,182,212,0.18)]'
                              : 'bg-slate-950/40 border-slate-800/80 hover:border-slate-700 hover:bg-slate-900/60'
                          }`}
                        >
                          <div className="flex items-center gap-3 min-w-0">
                            <div className="relative p-2.5 rounded-xl bg-slate-900 border border-slate-800 shrink-0">
                              {getPlatformIcon(peer.platform)}
                              <span className="absolute bottom-1 right-1 w-2 h-2 rounded-full bg-emerald-400 ring-2 ring-slate-900" />
                            </div>
                            <div className="min-w-0">
                              <strong className="text-xs sm:text-sm font-semibold text-slate-200 block truncate">
                                {peer.displayName || peer.deviceName || 'Thiết bị LAN'}
                              </strong>
                              <div className="flex items-center gap-1.5 text-[11px] text-slate-400 mt-0.5">
                                <span className="truncate max-w-[100px]">{peer.platform || 'Trình duyệt Web'}</span>
                                <span>·</span>
                                <code className="font-mono text-[10px] text-slate-400">
                                  {peer.connectionId.substring(0, 8)}...
                                </code>
                              </div>
                            </div>
                          </div>

                          <div className="shrink-0 pl-2">
                            {isSelected ? (
                              <span className="px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 text-[11px] font-semibold border border-cyan-500/40">
                                Đang chọn
                              </span>
                            ) : (
                              <span className="text-[11px] text-slate-400 hover:text-cyan-300 font-medium">
                                Vào chat →
                              </span>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                );
              })()}

              {/* Manual Connection ID Toggle */}
              <div className="pt-2 border-t border-slate-800/80">
                <button
                  type="button"
                  onClick={() => setShowManualInput(v => !v)}
                  className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center gap-1 transition-colors"
                >
                  <span>{showManualInput ? 'Ẩn nhập ID thủ công' : 'Hoặc kết nối bằng ID máy đối phương'}</span>
                </button>

                {showManualInput && (
                  <div className="mt-2.5 space-y-2">
                    <input
                      type="text"
                      value={manualConnectionId}
                      onChange={e => {
                        handleManualConnectionChange(e.target.value);
                        setSelectedPeer(null);
                      }}
                      placeholder="Dán ID phiên thiết bị (ví dụ: conn-xyz...)"
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-200 font-mono focus:border-cyan-500 focus:outline-none"
                    />
                  </div>
                )}
              </div>
            </div>

            {/* Quick Session Status Pill */}
            <div className="p-3.5 bg-slate-900/40 border border-slate-800/60 rounded-xl text-xs space-y-2">
              <div className="flex items-center justify-between text-slate-400">
                <span>Kênh WebRTC P2P:</span>
                <Tag tone={peerStateTone[peerState]}>{peerStateLabel[peerState]}</Tag>
              </div>
              {testResult?.passed && (
                <div className="flex items-center justify-between text-emerald-400 pt-1 border-t border-slate-800/40 text-[11px]">
                  <span className="flex items-center gap-1">
                    <ShieldCheck size={13} /> Độ trễ RTCDataChannel:
                  </span>
                  <span className="font-mono font-bold">{testResult.roundTripMs} ms</span>
                </div>
              )}
            </div>
          </div>

          {/* --------------------------------------------------------------------- */}
          {/* RIGHT PANE: P2P Conversation & Live Drop Stream (8 cols)              */}
          {/* --------------------------------------------------------------------- */}
          <div className={`lg:col-span-7 xl:col-span-8 space-y-4 ${!selectedPeer && !manualConnectionId && showMobilePeerList ? 'hidden lg:block' : 'block'}`}>
            {!selectedPeer && !manualConnectionId ? (
              <div className="p-10 sm:p-16 bg-slate-900/70 border border-slate-800/80 rounded-2xl text-center space-y-4 backdrop-blur-md">
                <div className="p-4 rounded-3xl bg-cyan-950/40 text-cyan-400 border border-cyan-500/30 w-fit mx-auto shadow-lg shadow-cyan-500/10">
                  <MessageSquare size={44} />
                </div>
                <div className="max-w-md mx-auto space-y-1.5">
                  <strong className="text-lg font-bold text-slate-100 block">
                    Phòng Trò Chuyện & Truyền Tệp P2P Trực Tiếp
                  </strong>
                  <p className="text-xs sm:text-sm text-slate-400 leading-relaxed">
                    Chọn một thiết bị để nhắn tin trực tiếp và gửi tệp. Người nhận xác nhận trước khi truyền; nội dung được kiểm tra trước khi hoàn tất.
                  </p>
                </div>
                <div className="pt-2 flex flex-wrap justify-center gap-2.5 text-xs text-slate-400">
                  <span className="px-3 py-1 bg-slate-950/60 border border-slate-800 rounded-lg">🔒 Mã hóa đầu cuối WebRTC</span>
                  <span className="px-3 py-1 bg-slate-950/60 border border-slate-800 rounded-lg">⚡ Tốc độ đường truyền LAN</span>
                  <span className="px-3 py-1 bg-slate-950/60 border border-slate-800 rounded-lg">📂 Không nén dung lượng</span>
                </div>
              </div>
            ) : (
              <div
                onDragOver={handleChatDragOver}
                onDragLeave={handleChatDragLeave}
                onDrop={handleChatDrop}
                className="relative bg-slate-900/85 border border-slate-800/80 rounded-2xl backdrop-blur-md flex flex-col h-[680px] shadow-2xl overflow-hidden"
              >
                {/* Drag and Drop Overlay */}
                {isChatDragging && (
                  <div className="absolute inset-0 z-30 bg-cyan-950/80 border-2 border-dashed border-cyan-400 rounded-2xl flex flex-col items-center justify-center gap-3 backdrop-blur-sm animate-in fade-in duration-150">
                    <div className="p-4 rounded-2xl bg-cyan-500/20 text-cyan-300 animate-bounce">
                      <UploadCloud size={44} />
                    </div>
                    <strong className="text-base font-bold text-cyan-200">
                      Thả tệp vào đây để gửi trực tiếp!
                    </strong>
                    <span className="text-xs text-cyan-400">
                      Gửi tới {selectedPeer?.deviceName || 'Thiết bị đối phương'} qua kênh RTCDataChannel
                    </span>
                  </div>
                )}

                {/* 1. Chat Header */}
                <div className="p-3.5 sm:p-4 bg-slate-950/70 border-b border-slate-800/80 flex flex-wrap items-center justify-between gap-3 shrink-0">
                  <div className="flex flex-1 items-center gap-2.5 min-w-0">
                    <button
                      type="button"
                      onClick={() => setShowMobilePeerList(true)}
                      className="lg:hidden p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300"
                      title="Quay lại danh sách"
                    >
                      <ArrowLeft size={16} />
                    </button>
                    <div className="p-2 rounded-xl bg-slate-900 border border-slate-800 text-cyan-400 shrink-0">
                      {getPlatformIcon(selectedPeer?.platform)}
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <strong className="text-sm font-bold text-slate-100 truncate block">
                          {selectedPeer?.displayName || selectedPeer?.deviceName || 'Thiết bị P2P'}
                        </strong>
                        <span className="w-2 h-2 rounded-full bg-emerald-400 shrink-0" />
                      </div>
                      <div className="flex items-center gap-2 text-[11px] text-slate-400 truncate">
                        <span>{selectedPeer?.platform || 'LAN Peer'}</span>
                        <span>·</span>
                        <code className="text-cyan-300/80 font-mono text-[10px]">
                          {(selectedPeer?.connectionId || manualConnectionId).substring(0, 12)}...
                        </code>
                      </div>
                    </div>
                  </div>

                  <div className="flex w-full sm:w-auto flex-wrap items-center gap-2 shrink-0">
                    <Tag tone={peerStateTone[peerState]}>{peerStateLabel[peerState]}</Tag>

                    {chatMessages.length > 0 && (
                      <button
                        type="button"
                        onClick={() => clearChatMessages()}
                        className="p-1.5 rounded-lg bg-slate-800 hover:bg-rose-950/50 hover:text-rose-300 text-slate-400 transition-colors"
                        title="Xóa lịch sử trò chuyện"
                      >
                        <Trash2 size={14} />
                      </button>
                    )}

                    {peerState === 'CONNECTED' ? (
                      <button
                        type="button"
                        onClick={() => cancelConnection('Ngắt bởi người dùng')}
                        className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-slate-800 hover:bg-rose-950/50 hover:text-rose-300 text-slate-300 transition-colors"
                      >
                        Ngắt kết nối
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={handleConnectPeer}
                        className="px-3 py-1 text-xs font-bold rounded-lg bg-gradient-to-r from-cyan-500 to-emerald-500 text-slate-950 hover:from-cyan-400 hover:to-emerald-400 transition-all shadow-sm"
                      >
                        Mở P2P
                      </button>
                    )}
                  </div>
                </div>

                {/* 2. Chat Timeline (Messages & File Bubbles) */}
                <div className="flex-1 p-4 overflow-y-auto space-y-3.5 bg-slate-950/30">
                  {chatMessages.length === 0 ? (
                    <div className="h-full flex flex-col items-center justify-center text-center p-6 space-y-2.5 text-slate-400">
                      <div className="p-3 bg-slate-900/60 rounded-2xl text-slate-400">
                        <MessageSquare size={28} />
                      </div>
                      <strong className="text-xs sm:text-sm font-semibold text-slate-300">
                        Chưa có tin nhắn trong phiên trò chuyện này
                      </strong>
                      <p className="text-[11px] text-slate-400 max-w-sm">
                        Gõ tin nhắn bên dưới hoặc kéo thả ảnh/tệp tin vào khung này để bắt đầu trao đổi dữ liệu trực tiếp.
                      </p>
                    </div>
                  ) : (
                    chatMessages.map(msg => {
                      const isMe = msg.direction === 'sent';
                      return (
                        <div
                          key={msg.id}
                          className={`flex flex-col ${isMe ? 'items-end' : 'items-start'} space-y-1`}
                        >
                          <div className="flex items-center gap-1.5 text-[10px] text-slate-400 px-1">
                            <span className="font-semibold text-slate-400">
                              {isMe ? 'Bạn' : msg.senderName}
                            </span>
                            <span>·</span>
                            <span>{formatRelativeTime(msg.timestamp)}</span>
                          </div>

                          {msg.type === 'file' ? (
                            <div
                              className={`p-3.5 rounded-2xl border max-w-sm sm:max-w-md space-y-2.5 ${
                                isMe
                                  ? 'bg-gradient-to-br from-cyan-950/60 to-slate-900 border-cyan-500/40 text-slate-100 shadow-md'
                                  : 'bg-slate-900/90 border-slate-800 text-slate-100 shadow-md'
                              }`}
                            >
                              <div className="flex items-center gap-3">
                                <FileGlyph name={msg.fileMeta?.name || 'file.dat'} />
                                <div className="min-w-0">
                                  <strong className="text-xs sm:text-sm font-bold text-slate-100 block truncate">
                                    {msg.fileMeta?.name || 'Tệp đính kèm'}
                                  </strong>
                                  <span className="text-[11px] text-slate-400 block font-mono">
                                    {msg.fileMeta ? formatBytes(msg.fileMeta.size) : '—'}
                                  </span>
                                </div>
                              </div>

                              {msg.fileMeta?.blobUrl && (
                                <a
                                  href={msg.fileMeta.blobUrl}
                                  download={msg.fileMeta.name}
                                  className="w-full py-1.5 px-3 rounded-xl text-xs font-bold bg-gradient-to-r from-emerald-500 to-teal-400 hover:from-emerald-400 hover:to-teal-300 text-slate-950 transition-all flex items-center justify-center gap-1.5 shadow-sm"
                                >
                                  <Download size={13} /> Tải tệp về máy
                                </a>
                              )}
                            </div>
                          ) : (
                            <div
                              className={`group relative p-3 sm:px-4 sm:py-2.5 rounded-2xl text-xs sm:text-sm max-w-[85%] sm:max-w-md break-words whitespace-pre-wrap leading-relaxed ${
                                isMe
                                  ? 'bg-gradient-to-r from-cyan-600 to-sky-600 text-white rounded-br-none shadow-md shadow-cyan-600/10'
                                  : 'bg-slate-900 border border-slate-800/80 text-slate-100 rounded-bl-none shadow-md'
                              }`}
                            >
                              {msg.text}
                              {msg.storageError && <div className="mt-1 text-[10px] text-amber-200">Chưa lưu được lịch sử: {msg.storageError}</div>}

                              {/* Message Delivery Checkmark & Copy Action */}
                              <div className="flex items-center justify-end gap-1 text-[10px] mt-1 opacity-75">
                                {isMe && !msg.ack && msg.status !== 'cancelled' && <><button onClick={() => void retryMessage(msg.id).catch(err => setTransferError(errorMessage(err)))} className="underline">Gửi lại</button><button onClick={() => void cancelMessage(msg.id).catch(err => setTransferError(errorMessage(err)))} className="ml-2 underline">Hủy</button></>}
                                {isMe && msg.status === 'cancelled' && <span>Đã hủy gửi</span>}
                                {isMe && (
                                  <span title={msg.ack ? 'Bên nhận đã lưu lịch sử' : msg.received ? 'Bên nhận đã hiển thị · đang lưu lịch sử' : msg.pending ? 'Đang chờ gửi' : 'Đã gửi qua mạng'}>
                                    {msg.pending ? '🕒' : (msg.ack ? '✓✓' : '✓')}
                                  </span>
                                )}
                              </div>
                            </div>
                          )}
                        </div>
                      );
                    })
                  )}

                  {/* Typing Indicator Bubble */}
                  {peerTyping && (
                    <div className="flex items-center gap-2 text-xs text-cyan-400 bg-slate-900/60 border border-slate-800 px-3 py-1.5 rounded-2xl w-fit animate-pulse">
                      <span className="flex gap-1">
                        <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-bounce" />
                        <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-bounce [animation-delay:0.2s]" />
                        <span className="w-1.5 h-1.5 rounded-full bg-cyan-400 animate-bounce [animation-delay:0.4s]" />
                      </span>
                      <span>{peerTyping.senderName} đang nhập tin nhắn...</span>
                    </div>
                  )}

                  <div ref={chatBottomRef} />
                </div>

                {/* Error Notice in Chat */}
                {transferError && (
                  <div className="px-4 py-2 bg-rose-950/60 border-t border-rose-800/40 text-rose-300 text-xs flex items-center justify-between">
                    <span>{transferError}</span>
                    <button type="button" onClick={() => setTransferError('')} className="text-rose-400 hover:text-rose-200">
                      <X size={14} />
                    </button>
                  </div>
                )}

                {/* 3. Chat Composer Bar */}
                <form
                  onSubmit={handleSendChat}
                  className="p-3 bg-slate-950/90 border-t border-slate-800/80 flex items-center gap-2 shrink-0"
                >
                  {/* Hidden File Picker Inputs */}
                  <input
                    ref={chatAttachmentInputRef}
                    type="file"
                    className="hidden"
                    onChange={e => {
                      if (e.target.files && e.target.files[0]) {
                        handleSendFileDirect(e.target.files[0]);
                        e.target.value = '';
                      }
                    }}
                  />
                  <input
                    ref={chatMediaInputRef}
                    type="file"
                    accept="image/*,video/*"
                    className="hidden"
                    onChange={e => {
                      if (e.target.files && e.target.files[0]) {
                        handleSendFileDirect(e.target.files[0]);
                        e.target.value = '';
                      }
                    }}
                  />

                  {/* Attachment buttons */}
                  <button
                    type="button"
                    onClick={() => chatAttachmentInputRef.current?.click()}
                    title="Đính kèm tệp tin"
                    className="p-2 sm:p-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-slate-300 hover:text-cyan-400 transition-colors shrink-0"
                  >
                    <Paperclip size={17} />
                  </button>
                  <button
                    type="button"
                    onClick={() => chatMediaInputRef.current?.click()}
                    title="Gửi ảnh / video"
                    className="p-2 sm:p-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-slate-300 hover:text-emerald-400 transition-colors shrink-0"
                  >
                    <Image size={17} />
                  </button>

                  {/* Text Input */}
                  <input
                    type="text"
                    value={chatInput}
                    onChange={e => handleChatInputChange(e.target.value)}
                    placeholder="Nhập tin nhắn hoặc kéo thả tệp vào khung..."
                    className="flex-1 bg-slate-900/90 border border-slate-800 rounded-xl px-3.5 py-2.5 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:border-cyan-500 focus:outline-none"
                  />

                  {/* Send Button */}
                  <button
                    type="submit"
                    disabled={!chatInput.trim()}
                    className="p-2.5 sm:px-4 sm:py-2.5 rounded-xl bg-gradient-to-r from-cyan-500 to-sky-500 hover:from-cyan-400 hover:to-sky-400 text-slate-950 font-bold transition-all disabled:opacity-40 disabled:cursor-not-allowed shadow-md shadow-cyan-500/10 flex items-center gap-1.5 shrink-0"
                  >
                    <Send size={16} />
                    <span className="hidden sm:inline text-xs font-bold">Gửi</span>
                  </button>
                </form>
              </div>
            )}
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 6. TAB: FILE DROP & RADAR DISCOVERY */}
      {/* ========================================================================= */}
      {activeTab === 'send' && (
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          {/* LEFT: Radar & Online LAN Devices (5 cols) */}
          <div className="lg:col-span-5 space-y-4">
            <div className="p-5 bg-slate-900/70 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Wifi size={18} className="text-cyan-400" />
                    <span className="absolute -top-1 -right-1 w-2 h-2 rounded-full bg-cyan-400 animate-ping" />
                  </div>
                  <strong className="text-sm font-bold text-slate-200">
                    Radar quét thiết bị LAN ({onlinePeers.length})
                  </strong>
                </div>

                <button
                  type="button"
                  onClick={handleScanNow}
                  className="flex items-center gap-1 px-2.5 py-1 text-xs rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors"
                >
                  <RefreshCw size={12} className={isScanning ? 'animate-spin text-cyan-400' : ''} />
                  <span>Quét lại</span>
                </button>
              </div>

              {/* Online Peer Device List */}
              {onlinePeers.length > 0 ? (
                <div className="space-y-2.5 max-h-[380px] overflow-y-auto pr-1">
                  {onlinePeers.map(peer => {
                    const isSelected = selectedPeer?.connectionId === peer.connectionId;
                    return (
                      <div
                        key={peer.connectionId}
                        onClick={() => handleSelectPeer(peer)}
                        className={`p-3.5 rounded-xl border transition-all cursor-pointer flex items-center justify-between ${
                          isSelected
                            ? 'bg-gradient-to-r from-cyan-950/50 to-slate-900 border-cyan-500 shadow-[0_0_15px_rgba(6,182,212,0.15)]'
                            : 'bg-slate-950/40 border-slate-800 hover:border-slate-700 hover:bg-slate-900/60'
                        }`}
                      >
                        <div className="flex items-center gap-3 min-w-0">
                          <div className="p-2.5 rounded-xl bg-slate-900 border border-slate-800 shrink-0">
                            {getPlatformIcon(peer.platform)}
                          </div>
                          <div className="min-w-0">
                            <strong className="text-sm font-semibold text-slate-200 block truncate">
                              {peer.displayName || peer.deviceName || 'Thiết bị LAN'}
                            </strong>
                            <div className="flex items-center gap-2 text-[11px] text-slate-400 mt-0.5">
                              <span className="text-slate-400">{peer.platform || 'Browser Web'}</span>
                              <span>·</span>
                              <code className="text-slate-400 font-mono text-[10px]">
                                {peer.connectionId.substring(0, 10)}...
                              </code>
                            </div>
                          </div>
                        </div>

                        <div className="shrink-0 pl-2">
                          {isSelected ? (
                            <span className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 text-xs font-semibold border border-cyan-500/40">
                              <Check size={12} /> Đã chọn
                            </span>
                          ) : (
                            <span className="text-xs text-slate-400 hover:text-cyan-300 font-medium">
                              Chọn gửi →
                            </span>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="p-8 border border-dashed border-slate-800 rounded-xl text-center space-y-3 bg-slate-950/30">
                  <div className="relative inline-block mx-auto">
                    <Radio size={36} className="text-slate-400 animate-pulse" />
                  </div>
                  <div>
                    <strong className="text-sm font-semibold text-slate-300 block">
                      Đang tìm kiếm thiết bị lân cận...
                    </strong>
                    <p className="text-xs text-slate-400 mt-1 max-w-xs mx-auto">
                      Mở LANDrop trên một máy tính, tablet hoặc điện thoại khác trong cùng mạng Wi-Fi/LAN để kết nối ngay.
                    </p>
                  </div>
                </div>
              )}

              {/* Manual Connection ID Toggle */}
              <div className="pt-2 border-t border-slate-800/80">
                <button
                  type="button"
                  onClick={() => setShowManualInput(v => !v)}
                  className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 transition-colors"
                >
                  <span>{showManualInput ? 'Ẩn nhập ID thủ công' : 'Hoặc nhập ID phiên thủ công'}</span>
                </button>

                {showManualInput && (
                  <div className="mt-2.5 space-y-2">
                    <input
                      type="text"
                      value={manualConnectionId}
                      onChange={e => {
                        handleManualConnectionChange(e.target.value);
                        setSelectedPeer(null);
                      }}
                      placeholder="Dán ID phiên thiết bị (ví dụ: conn-xyz...)"
                      className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-slate-200 font-mono focus:border-cyan-500 focus:outline-none"
                    />
                  </div>
                )}
              </div>
            </div>

            {/* P2P Diagnostics Mini Card */}
            <div className="p-4 bg-slate-900/40 border border-slate-800/60 rounded-xl text-xs space-y-2">
              <div className="flex items-center justify-between text-slate-400">
                <span>Trạng thái kết nối:</span>
                <Tag tone={peerStateTone[peerState]}>{peerStateLabel[peerState]}</Tag>
              </div>
              {testResult?.passed && (
                <div className="flex items-center justify-between text-emerald-400 pt-1 border-t border-slate-800/40">
                  <span className="flex items-center gap-1">
                    <ShieldCheck size={14} /> Độ trễ RTCDataChannel:
                  </span>
                  <span className="font-mono font-bold">{testResult.roundTripMs} ms</span>
                </div>
              )}
            </div>
          </div>

          {/* RIGHT: Glowing Dropzone & Send Action (7 cols) */}
          <div className="lg:col-span-7 space-y-4">
            <div className="p-6 bg-slate-900/70 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-6">
              <div>
                <strong className="text-base font-bold text-slate-100 block">
                  Chọn & Thả tệp tin cần truyền
                </strong>
                <p className="text-xs text-slate-400 mt-0.5">
                  Tệp được cắt nhỏ thành các block 16KB và truyền trực tiếp qua WebRTC DataChannel tốc độ dây mạng LAN.
                </p>
              </div>

              {/* Glowing Dropzone */}
              <div
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className={`relative p-8 sm:p-12 border-2 border-dashed rounded-2xl text-center cursor-pointer transition-all duration-300 flex flex-col items-center justify-center gap-3 ${
                  isDragging
                    ? 'border-cyan-400 bg-cyan-950/20 shadow-[0_0_25px_rgba(6,182,212,0.25)] scale-[1.01]'
                    : selectedFile
                    ? 'border-emerald-500/60 bg-emerald-950/10'
                    : 'border-slate-700/80 hover:border-cyan-500/50 bg-slate-950/40 hover:bg-slate-900/50'
                }`}
              >
                <input
                  ref={fileInputRef}
                  type="file"
                  className="hidden"
                  onChange={handleFileInputChange}
                />

                <div className={`p-4 rounded-2xl ${selectedFile ? 'bg-emerald-500/20 text-emerald-400' : 'bg-slate-800/80 text-cyan-400'} shadow-md`}>
                  {selectedFile ? <FileGlyph name={selectedFile.name} /> : <UploadCloud size={32} />}
                </div>

                {selectedFile ? (
                  <div className="space-y-1">
                    <strong className="text-sm sm:text-base font-bold text-emerald-300 block truncate max-w-sm sm:max-w-md">
                      {selectedFile.name}
                    </strong>
                    <span className="text-xs text-slate-400 block font-mono">
                      {formatBytes(selectedFile.size)} · Sẵn sàng truyền
                    </span>
                    <button
                      type="button"
                      onClick={e => {
                        e.stopPropagation();
                        setSelectedFile(null);
                        if (fileInputRef.current) fileInputRef.current.value = '';
                      }}
                      className="text-xs text-rose-400 hover:text-rose-300 underline pt-1 inline-block"
                    >
                      Chọn tệp khác
                    </button>
                  </div>
                ) : (
                  <div className="space-y-1">
                    <strong className="text-sm font-semibold text-slate-200 block">
                      Kéo & Thả tệp vào đây, hoặc nhấn để duyệt
                    </strong>
                    <p className="text-xs text-slate-400">
                      Hỗ trợ mọi định dạng: Ảnh, Video, Tài liệu, File nén (Không giới hạn dung lượng)
                    </p>
                  </div>
                )}
              </div>

              {/* Error Notice */}
              {transferError && <Notice danger>{transferError}</Notice>}

              {/* Target Confirmation & Send Button */}
              <div className="pt-2 flex flex-col sm:flex-row items-stretch sm:items-center justify-between gap-3">
                <div className="text-xs text-slate-400">
                  {selectedPeer ? (
                    <span>
                      Gửi tới: <strong className="text-cyan-300">{selectedPeer.displayName || selectedPeer.deviceName}</strong> ({selectedPeer.platform})
                    </span>
                  ) : manualConnectionId ? (
                    <span>
                      Gửi tới ID: <code className="text-slate-300 font-mono">{manualConnectionId.substring(0, 16)}...</code>
                    </span>
                  ) : (
                    <span className="text-amber-400">⚠️ Vui lòng chọn thiết bị nhận bên cột Radar</span>
                  )}
                </div>

                <button
                  type="button"
                  onClick={handleInitiateTransfer}
                  disabled={isSending || !selectedFile || (!selectedPeer && !manualConnectionId.trim())}
                  className="px-6 py-3 rounded-xl font-bold text-xs sm:text-sm bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 shadow-lg shadow-cyan-500/20 disabled:opacity-40 disabled:cursor-not-allowed transition-all flex items-center justify-center gap-2 min-h-[44px]"
                >
                  {isSending ? (
                    <>
                      <Loader2 size={16} className="animate-spin" />
                      <span>Đang truyền tệp...</span>
                    </>
                  ) : (
                    <>
                      <Send size={16} />
                      <span>Bắt đầu truyền ngay</span>
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 6. TAB: QUICK TEXT / CLIPBOARD DROP */}
      {/* ========================================================================= */}
      {activeTab === 'text' && (
        <div className="space-y-6">
          <div className="p-6 bg-slate-900/70 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-4">
            <div>
              <strong className="text-base font-bold text-slate-100 block">
                Bản ghi nhớ & Bộ nhớ tạm qua LAN (LAN Clipboard Drop)
              </strong>
              <p className="text-xs text-slate-400 mt-0.5">
                Chia sẻ liên kết web, đoạn mã, mật khẩu, mã OTP hoặc ghi chú giữa các thiết bị tức thì.
              </p>
            </div>

            <form onSubmit={handleSendQuickText} className="space-y-3">
              <div className="relative">
                <textarea
                  rows={3}
                  value={quickTextInput}
                  onChange={e => setQuickTextInput(e.target.value)}
                  placeholder="Nhập hoặc dán nội dung văn bản, đường dẫn link, mã OTP cần gửi..."
                  className="w-full bg-slate-950/80 border border-slate-800 rounded-xl p-3.5 text-sm text-slate-200 placeholder-slate-500 focus:border-cyan-500 focus:outline-none resize-none"
                  onKeyDown={e => {
                    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                      e.preventDefault();
                      handleSendQuickText(e);
                    }
                  }}
                />
              </div>

              {transferError && <Notice danger>{transferError}</Notice>}

              <div className="flex items-center justify-between">
                <span className="text-[11px] text-slate-400">
                  Mẹo: Nhấn Ctrl + Enter để gửi nhanh. Kênh P2P phải đang mở.
                </span>
                <button
                  type="submit"
                  disabled={!quickTextInput.trim() || peerState !== 'CONNECTED'}
                  className="px-5 py-2.5 rounded-xl text-xs font-bold bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 shadow-md shadow-cyan-500/20 disabled:opacity-40 transition-all flex items-center gap-1.5 min-h-[40px]"
                >
                  <Send size={14} /> Gửi văn bản
                </button>
              </div>
            </form>
          </div>

          {/* Quick Messages Timeline */}
          <div className="p-6 bg-slate-900/70 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-4">
            <div className="flex items-center justify-between">
              <strong className="text-sm font-bold text-slate-200">
                Lịch sử trao đổi trong phiên ({quickMessages.length})
              </strong>
              {quickMessages.length > 0 && (
                <button
                  type="button"
                  onClick={clearQuickMessages}
                  className="text-xs text-slate-400 hover:text-rose-400 flex items-center gap-1 transition-colors"
                >
                  <Trash2 size={13} /> Xóa lịch sử
                </button>
              )}
            </div>

            {quickMessages.length > 0 ? (
              <div className="space-y-3">
                {quickMessages.map(msg => (
                  <div
                    key={msg.id}
                    className={`p-4 rounded-xl border flex flex-col sm:flex-row sm:items-start justify-between gap-3 ${
                      msg.direction === 'sent'
                        ? 'bg-slate-950/60 border-slate-800'
                        : 'bg-cyan-950/20 border-cyan-800/40'
                    }`}
                  >
                    <div className="space-y-1.5 min-w-0">
                      <div className="flex items-center gap-2 text-xs">
                        <strong className={msg.direction === 'sent' ? 'text-slate-300' : 'text-cyan-400'}>
                          {msg.direction === 'sent' ? 'Bạn đã gửi' : `Nhận từ ${msg.senderName}`}
                        </strong>
                        <span className="text-slate-400">· {formatRelativeTime(msg.timestamp)}</span>
                      </div>
                      <p className="text-sm text-slate-200 font-mono whitespace-pre-wrap break-words selection:bg-cyan-500/30">
                        {msg.text}
                      </p>
                    </div>

                    <button
                      type="button"
                      onClick={() => handleCopyText(msg.id, msg.text)}
                      className="self-end sm:self-start shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors"
                    >
                      {copiedTextId === msg.id ? (
                        <>
                          <Check size={13} className="text-emerald-400" />
                          <span className="text-emerald-400">Đã sao chép</span>
                        </>
                      ) : (
                        <>
                          <Copy size={13} />
                          <span>Sao chép</span>
                        </>
                      )}
                    </button>
                  </div>
                ))}
              </div>
            ) : (
              <Empty
                icon={<FileText size={24} />}
                title="Chưa có tin nhắn văn bản nào"
                description="Các liên kết hoặc đoạn văn bản chia sẻ giữa các thiết bị sẽ hiển thị tại đây."
              />
            )}
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* 7. TAB: RECEIVED FILES TRAY */}
      {/* ========================================================================= */}
      {activeTab === 'received' && (
        <div className="p-6 bg-slate-900/70 border border-slate-800/80 rounded-2xl backdrop-blur-md space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <strong className="text-base font-bold text-slate-100 block">
                Tệp đã nhận từ thiết bị khác ({receivedFiles.length})
              </strong>
              <p className="text-xs text-slate-400 mt-0.5">
                Các tệp được lưu trong bộ nhớ trình duyệt phiên này. Bạn có thể nhấn để tải về máy bất cứ lúc nào.
              </p>
            </div>
            {receivedFiles.length > 0 && (
              <button
                type="button"
                onClick={clearReceivedFiles}
                className="text-xs text-slate-400 hover:text-rose-400 flex items-center gap-1 transition-colors"
              >
                <Trash2 size={13} /> Xóa danh sách
              </button>
            )}
          </div>

          {receivedFiles.length > 0 ? (
            <div className="space-y-3">
              {receivedFiles.map(file => (
                <div
                  key={file.id}
                  className="p-4 bg-slate-950/60 border border-slate-800 rounded-xl flex items-center justify-between gap-4"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <FileGlyph name={file.fileName} />
                    <div className="min-w-0">
                      <strong className="text-sm font-semibold text-slate-200 block truncate">
                        {file.fileName}
                      </strong>
                      <span className="text-xs text-slate-400 block mt-0.5">
                        {formatBytes(file.fileSize)} · Từ {file.senderName} · {formatRelativeTime(file.receivedAt)}
                      </span>
                    </div>
                  </div>

                  <a
                    href={file.blobUrl}
                    download={file.fileName}
                    className="px-4 py-2 rounded-xl text-xs font-bold bg-gradient-to-r from-emerald-500 to-teal-400 hover:from-emerald-400 hover:to-teal-300 text-slate-950 transition-all flex items-center gap-1.5 shrink-0 shadow-md shadow-emerald-500/10"
                  >
                    <Download size={14} /> Tải về máy
                  </a>
                </div>
              ))}
            </div>
          ) : (
            <Empty
              icon={<Download size={24} />}
              title="Chưa có tệp nào được nhận"
              description="Khi thiết bị khác gửi tệp tới bạn qua WebRTC, tệp sẽ tự động lắp ráp và hiển thị ở đây."
            />
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* 8. TAB: SERVER LOOKUP (LEGACY COMPATIBILITY) */}
      {/* ========================================================================= */}
      {activeTab === 'lookup' && (
        <Panel
          title="Tra cứu yêu cầu truyền trên máy chủ"
          description="Kiểm tra thông tin bản ghi truyền tệp đã đăng ký trên server bằng mã định danh UUID."
        >
          <div className="panel-body space-y-4">
            <form
              className="form-stack"
              onSubmit={async event => {
                event.preventDefault();
                setLookupBusy(true);
                setLookupFailure('');
                setLookupInfo(null);
                try {
                  const response = await request<{ data: TransferInfo }>(
                    `/api/transfers/${encodeURIComponent(lookupCode.trim())}`
                  );
                  if (response.data.file?.workspaceId !== id) {
                    throw new Error('Yêu cầu không thuộc không gian làm việc hiện tại.');
                  }
                  setLookupInfo(response.data);
                } catch (error) {
                  setLookupFailure(errorMessage(error));
                } finally {
                  setLookupBusy(false);
                }
              }}
            >
              <Field
                label="Mã transfer (UUID)"
                value={lookupCode}
                onChange={event => setLookupCode(event.target.value)}
                required
                pattern="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                placeholder="Ví dụ: e3b0c442-98fc-1c14-9afb-4c72e0439ef2"
              />
              <div className="form-actions">
                <Action type="submit" busy={lookupBusy}>
                  <Search size={15} /> Tra cứu
                </Action>
              </div>
            </form>

            {lookupFailure && <Notice danger>{lookupFailure}</Notice>}

            {lookupInfo && (
              <dl className="detail-grid mt-4">
                <dt>Tài liệu</dt>
                <dd>{lookupInfo.file?.name || lookupInfo.fileId}</dd>
                <dt>Dung lượng</dt>
                <dd>{lookupInfo.file ? formatBytes(lookupInfo.file.size) : '—'}</dd>
                <dt>Trạng thái</dt>
                <dd>
                  <Tag tone={lookupInfo.status === 'COMPLETED' ? 'green' : 'blue'}>{lookupInfo.status}</Tag>
                </dd>
                <dt>Người gửi</dt>
                <dd>{lookupInfo.senderUserId}</dd>
                <dt>Người nhận</dt>
                <dd>{lookupInfo.receiverUserId}</dd>
                <dt>Đăng ký lúc</dt>
                <dd>{new Date(lookupInfo.createdAt).toLocaleString('vi-VN')}</dd>
              </dl>
            )}
          </div>
        </Panel>
      )}

      {/* ========================================================================= */}
      {/* 9. Technical Diagnostics Collapsible Drawer */}
      {/* ========================================================================= */}
      <div className="pt-2">
        <button
          type="button"
          onClick={() => setShowDiagnostics(v => !v)}
          className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1.5 transition-colors"
        >
          <Info size={14} />
          <span>{showDiagnostics ? 'Ẩn thông số chẩn đoán WebRTC' : 'Xem thông số chẩn đoán kỹ thuật WebRTC'}</span>
        </button>

        {showDiagnostics && (
          <div className="mt-3 p-4 bg-slate-950/80 border border-slate-800 rounded-xl space-y-3">
            <div className="flex items-center justify-between text-xs text-slate-400">
              <strong className="uppercase tracking-wider">WebRTC Internal State</strong>
              <span>Cập nhật: {new Date(diagnostics.lastUpdated).toLocaleTimeString()}</span>
            </div>
            <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
              <div>
                <dt className="text-slate-400">Signaling State</dt>
                <dd className="font-mono text-slate-200">{diagnostics.signalingState}</dd>
              </div>
              <div>
                <dt className="text-slate-400">Peer Connection</dt>
                <dd className="font-mono text-slate-200">{diagnostics.peerConnectionState}</dd>
              </div>
              <div>
                <dt className="text-slate-400">Data Channel</dt>
                <dd className="font-mono text-slate-200">{diagnostics.dataChannelState}</dd>
              </div>
              <div>
                <dt className="text-slate-400">Độ trễ Round-trip</dt>
                <dd className="font-mono text-emerald-400 font-bold">
                  {diagnostics.roundTripLatencyMs !== null ? `${diagnostics.roundTripLatencyMs} ms` : '—'}
                </dd>
              </div>
              <div>
                <dt className="text-slate-400">Chat nhận · RTT</dt>
                <dd className="font-mono text-emerald-400">{diagnostics.chatReceiveRttMs === undefined ? '—' : `${diagnostics.chatReceiveRttMs.toFixed(2)} ms`}</dd>
              </div>
              <div>
                <dt className="text-slate-400">Chat lưu bền · RTT</dt>
                <dd className="font-mono text-slate-200">{diagnostics.chatDurableRttMs === undefined ? '—' : `${diagnostics.chatDurableRttMs.toFixed(2)} ms`}</dd>
              </div>
            </dl>
          </div>
        )}
      </div>
      {/* 10. QR CODE & CONNECTION INFO MODAL                                       */}
      {/* ========================================================================= */}
      {showQrModal && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-md w-full p-6 space-y-4 shadow-2xl animate-in fade-in duration-200">
            <div className="flex items-center justify-between">
              <strong className="text-base font-bold text-slate-100">
                Thông tin phiên & Quét kết nối
              </strong>
              <button
                type="button"
                onClick={() => setShowQrModal(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-slate-200"
              >
                <X size={18} />
              </button>
            </div>

            <div className="p-4 bg-slate-950/80 rounded-xl border border-slate-800 text-center space-y-3">
              <QrCode size={48} className="text-cyan-400 mx-auto" />
              <div>
                <span className="text-xs text-slate-400 block">ID thiết bị của bạn:</span>
                <code className="text-xs font-mono text-cyan-300 break-all select-all font-semibold block mt-1">
                  {localConnectionId || 'Đang kết nối tới máy chủ...'}
                </code>
              </div>
              <button
                type="button"
                onClick={handleCopyMyId}
                className="px-4 py-2 rounded-xl text-xs font-semibold bg-slate-800 hover:bg-slate-700 text-slate-200 transition-colors flex items-center gap-1.5 mx-auto"
              >
                {copiedMyId ? <Check size={14} className="text-emerald-400" /> : <Copy size={14} />}
                <span>{copiedMyId ? 'Đã chép ID' : 'Sao chép ID phiên'}</span>
              </button>
            </div>

            <p className="text-xs text-slate-400 leading-relaxed text-center">
              Các thiết bị trong cùng mạng Wi-Fi hoặc mạng LAN chỉ cần mở trình duyệt và truy cập máy chủ LANDrop là có thể tự động nhìn thấy nhau qua Radar.
            </p>

            <button
              type="button"
              onClick={() => setShowQrModal(false)}
              className="w-full py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold transition-colors"
            >
              Đóng
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
