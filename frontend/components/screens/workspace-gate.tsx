'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  ArrowLeft,
  Check,
  Copy,
  Eye,
  EyeOff,
  FolderOpen,
  KeyRound,
  Lock,
  QrCode,
  ShieldAlert,
  Users,
  Zap,
} from 'lucide-react';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { errorMessage } from '@/lib/api/client';
import { Action, Field, Initials, Notice, Tag } from '../system/primitives';

export function WorkspaceGate({ id }: { id: string }) {
  const { preview, joinWorkspace } = useWorkspace();
  const { serverInfo } = useSession();
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copiedLink, setCopiedLink] = useState(false);
  const [showQr, setShowQr] = useState(false);
  const router = useRouter();

  if (!preview) {
    return (
      <div className="p-8 max-w-lg mx-auto text-center space-y-4">
        <ShieldAlert size={40} className="text-amber-400 mx-auto" />
        <h2 className="text-lg font-bold text-slate-100">Không tìm thấy không gian làm việc</h2>
        <p className="text-xs text-slate-400">
          Mã định danh không gian không tồn tại hoặc bạn không có quyền xem thông tin nhóm này.
        </p>
        <Link href="/workspaces" className="action action-secondary inline-flex items-center gap-2">
          <ArrowLeft size={14} /> Quay về danh sách nhóm
        </Link>
      </div>
    );
  }

  const joinUrl = typeof window !== 'undefined' ? `${window.location.origin}/w/${preview.id}` : `/w/${preview.id}`;
  const qrEndpoint = `${serverInfo.serverUrl.replace(/\/+$/, '')}/api/workspaces/${preview.id}/qr`;

  const handleCopyLink = () => {
    navigator.clipboard.writeText(joinUrl);
    setCopiedLink(true);
    setTimeout(() => setCopiedLink(false), 2000);
  };

  const handleJoin = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await joinWorkspace(password);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="min-h-[75vh] flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-slate-900/90 border border-slate-800 rounded-3xl p-6 sm:p-8 backdrop-blur-xl shadow-2xl space-y-6 animate-in fade-in zoom-in-95 duration-200">
        {/* Workspace Brand Head */}
        <div className="text-center space-y-3">
          <div className="w-16 h-16 rounded-2xl bg-gradient-to-tr from-cyan-600 to-emerald-500 p-0.5 mx-auto shadow-lg shadow-cyan-500/20">
            <div className="w-full h-full bg-slate-950 rounded-[14px] flex items-center justify-center text-cyan-300 font-extrabold text-2xl">
              {preview.name.charAt(0).toUpperCase()}
            </div>
          </div>
          <div>
            <h1 className="text-xl sm:text-2xl font-black text-slate-100 tracking-tight">
              {preview.name}
            </h1>
            <p className="text-xs text-slate-400 mt-1 line-clamp-2">
              {preview.description || 'Không gian trao đổi nội dung, thảo luận kênh và tài liệu nhóm.'}
            </p>
          </div>

          <div className="flex flex-wrap items-center justify-center gap-2 pt-1">
            <span className="px-2.5 py-1 rounded-full bg-slate-950/80 border border-slate-800 text-[11px] text-slate-300 flex items-center gap-1.5">
              <Users size={12} className="text-cyan-400" />
              <span>{preview.membersCount} thành viên</span>
            </span>
            <span className="px-2.5 py-1 rounded-full bg-slate-950/80 border border-slate-800 text-[11px] text-slate-300">
              Chủ nhóm: <strong className="text-slate-200 font-semibold">{preview.ownerName}</strong>
            </span>
          </div>
        </div>

        {/* Policy Notice Box */}
        {preview.joinPolicy === 'PASSWORD_PROTECTED' && (
          <div className="p-3.5 bg-cyan-950/40 border border-cyan-500/30 rounded-2xl flex items-center gap-3 text-xs text-cyan-200">
            <div className="p-2 bg-cyan-500/20 text-cyan-300 rounded-xl shrink-0">
              <Lock size={16} />
            </div>
            <div>
              <strong className="block font-semibold">Nhóm bảo vệ bằng mật khẩu</strong>
              <span className="text-[11px] text-cyan-400/80">
                Nhập mật khẩu do quản trị viên cung cấp để gia nhập và truy cập tài liệu.
              </span>
            </div>
          </div>
        )}

        {preview.joinPolicy === 'OPEN_LAN' && (
          <div className="p-3.5 bg-emerald-950/40 border border-emerald-500/30 rounded-2xl flex items-center gap-3 text-xs text-emerald-200">
            <div className="p-2 bg-emerald-500/20 text-emerald-300 rounded-xl shrink-0">
              <Zap size={16} />
            </div>
            <div>
              <strong className="block font-semibold">Nhóm công khai trong mạng LAN</strong>
              <span className="text-[11px] text-emerald-400/80">
                Không yêu cầu mật khẩu. Bạn có thể tham gia ngay lập tức.
              </span>
            </div>
          </div>
        )}

        {preview.joinPolicy === 'INVITE_ONLY' && (
          <div className="p-4 bg-rose-950/40 border border-rose-500/30 rounded-2xl space-y-2 text-xs text-rose-200 text-center">
            <ShieldAlert size={28} className="text-rose-400 mx-auto" />
            <strong className="block font-semibold">Không gian chỉ nhận lời mời trực tiếp</strong>
            <p className="text-[11px] text-slate-300 leading-relaxed">
              Nhóm này không mở tự do hoặc bằng mật khẩu. Vui lòng liên hệ chủ nhóm (<strong>{preview.ownerName}</strong>) để được thêm tài khoản vào danh sách thành viên.
            </p>
            <div className="pt-2">
              <Link href="/workspaces" className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold inline-block transition-colors">
                Quay lại danh sách nhóm
              </Link>
            </div>
          </div>
        )}

        {/* Action Form */}
        {preview.joinPolicy !== 'INVITE_ONLY' && (
          <form onSubmit={handleJoin} className="space-y-4">
            {error && <Notice danger>{error}</Notice>}

            {preview.joinPolicy === 'PASSWORD_PROTECTED' && (
              <div className="space-y-1.5">
                <label className="text-xs font-semibold text-slate-300 flex items-center justify-between">
                  <span className="flex items-center gap-1.5">
                    <KeyRound size={13} className="text-cyan-400" />
                    Mật khẩu tham gia nhóm
                  </span>
                  <button
                    type="button"
                    onClick={() => setShowPassword(v => !v)}
                    className="text-[11px] text-slate-400 hover:text-slate-200 flex items-center gap-1"
                  >
                    {showPassword ? <EyeOff size={12} /> : <Eye size={12} />}
                    <span>{showPassword ? 'Ẩn' : 'Hiện'}</span>
                  </button>
                </label>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    required
                    placeholder="Nhập mật khẩu nhóm..."
                    className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3.5 py-2.5 text-xs sm:text-sm text-slate-100 placeholder-slate-500 focus:border-cyan-500 focus:outline-none transition-colors"
                  />
                </div>
              </div>
            )}

            <button
              type="submit"
              disabled={busy || (preview.joinPolicy === 'PASSWORD_PROTECTED' && !password.trim())}
              className="w-full py-2.5 sm:py-3 rounded-xl bg-gradient-to-r from-cyan-500 to-emerald-500 hover:from-cyan-400 hover:to-emerald-400 text-slate-950 font-bold text-xs sm:text-sm transition-all shadow-lg shadow-cyan-500/20 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
            >
              <Zap size={16} />
              <span>{busy ? 'Đang xác thực...' : 'Tham gia không gian làm việc'}</span>
            </button>
          </form>
        )}

        {/* Sharing Strip: Copy Link & Show QR Code */}
        <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-400">
          <button
            type="button"
            onClick={handleCopyLink}
            className="flex items-center gap-1.5 hover:text-slate-200 transition-colors"
          >
            {copiedLink ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
            <span>{copiedLink ? 'Đã sao chép liên kết' : 'Sao chép liên kết'}</span>
          </button>

          <button
            type="button"
            onClick={() => setShowQr(v => !v)}
            className="flex items-center gap-1.5 hover:text-cyan-300 transition-colors"
          >
            <QrCode size={13} />
            <span>{showQr ? 'Ẩn mã QR' : 'Mã QR quét vào'}</span>
          </button>
        </div>

        {/* QR Code Collapsible Viewer */}
        {showQr && (
          <div className="p-4 bg-slate-950/90 rounded-2xl border border-slate-800 text-center space-y-2.5 animate-in fade-in duration-150">
            <span className="text-[11px] text-slate-400 block font-medium">
              Quét mã bằng điện thoại hoặc thiết bị khác trong cùng mạng LAN:
            </span>
            <div className="bg-white p-2.5 rounded-xl w-fit mx-auto shadow-md">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={qrEndpoint}
                alt="QR Code tham gia nhóm"
                className="w-44 h-44 object-contain"
                onError={e => {
                  // Fallback placeholder if server image generation fails
                  (e.target as HTMLElement).style.display = 'none';
                }}
              />
            </div>
            <code className="text-[10px] font-mono text-cyan-300 break-all select-all block">
              {joinUrl}
            </code>
          </div>
        )}
      </div>
    </div>
  );
}
