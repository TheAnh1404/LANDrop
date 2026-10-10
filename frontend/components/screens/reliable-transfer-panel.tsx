'use client';

import React, { useRef, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, Check, FileText, FolderDown, HardDrive, Loader2, Pause, Play, Plus, ShieldCheck, Trash2, X } from 'lucide-react';
import { useWebRTC } from '@/lib/webrtc/webrtc-provider';
import { formatBytes, formatSpeed, formatETA } from '@/lib/utils';
import type { P2PTask } from '@/lib/p2p/engine';

const statusLabels: Record<P2PTask['status'], string> = {
  queued: 'Trong hàng đợi', preparing: 'Đang đọc và kiểm tra tệp', waiting: 'Chờ người nhận', offered: 'Tệp được gửi đến',
  transferring: 'Đang truyền', paused: 'Đã tạm dừng', interrupted: 'Chờ kết nối lại', verifying: 'Đang xác minh và lưu',
  completed: 'Đã xác minh · SHA-256 khớp', cancelled: 'Đã hủy', rejected: 'Đã từ chối', failed: 'Cần thử lại',
};
type SavePicker = (options: { suggestedName: string }) => Promise<FileSystemFileHandle>;

export function ReliableTransferPanel({ onFiles, canSend }: { onFiles: (files: File[]) => Promise<void>; canSend: boolean }) {
  const { fileTasks, backgroundConnections, acceptFile, rejectFile, pauseFile, resumeFile, cancelFile, removeFile, downloadFile } = useWebRTC();
  const input = useRef<HTMLInputElement>(null);
  const resumeInput = useRef<HTMLInputElement>(null);
  const resumeId = useRef('');
  const [error, setError] = useState('');
  const [filter, setFilter] = useState<'active' | 'all'>('active');
  const [busy, setBusy] = useState(new Set<string>());
  const run = async (id: string, action: () => Promise<void>) => {
    setError(''); setBusy(previous => new Set(previous).add(id));
    try { await action(); } catch (failure) { if (!(failure instanceof DOMException && failure.name === 'AbortError')) setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(previous => { const next = new Set(previous); next.delete(id); return next; }); }
  };
  const active = fileTasks.filter(task => !['completed', 'cancelled', 'rejected'].includes(task.status));
  const verified = fileTasks.filter(task => task.status === 'completed' && task.verified).length;
  const displayed = (filter === 'active' ? active : fileTasks).slice().sort((a, b) => b.createdAt - a.createdAt);
  const diskAvailable = typeof window !== 'undefined' && window.isSecureContext && !!navigator.storage?.getDirectory;

  return <section aria-label="Hàng đợi chuyển tệp an toàn" className="overflow-hidden rounded-2xl border border-slate-700/70 bg-gradient-to-br from-slate-900 via-slate-950 to-cyan-950/30 shadow-xl">
    <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-800 p-5 sm:p-6">
      <div className="flex items-center gap-3">
        <div className="rounded-xl border border-cyan-400/20 bg-cyan-400/10 p-3 text-cyan-300"><ShieldCheck size={23} /></div>
        <div><p className="text-[10px] font-bold uppercase tracking-[.22em] text-cyan-400">TRUYỀN TRỰC TIẾP TRONG LAN</p><h2 className="mt-1 text-lg font-bold text-white">Tệp của bạn. Được lưu trọn vẹn.</h2><p className="mt-1 text-xs text-slate-400">Xác minh nội dung ở cả hai đầu · Chat vẫn hoạt động khi truyền</p></div>
      </div>
      <button disabled={!canSend} onClick={() => input.current?.click()} className="flex items-center gap-2 rounded-xl bg-cyan-400 px-4 py-2.5 text-xs font-bold text-slate-950 transition hover:bg-cyan-300 disabled:cursor-not-allowed disabled:opacity-40"><Plus size={16} />Thêm nhiều tệp</button>
      <input ref={input} type="file" multiple className="hidden" aria-label="Chọn các tệp để thêm vào hàng đợi" onChange={event => { const files = Array.from(event.target.files || []); event.target.value = ''; void run('queue', () => onFiles(files)); }} />
      <input ref={resumeInput} type="file" className="hidden" aria-label="Chọn lại tệp gốc để tiếp tục" onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void run(resumeId.current, () => resumeFile(resumeId.current, file)); }} />
    </div>
    <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-900/50 px-5 py-3 text-xs text-slate-400">
      <div className="flex flex-wrap gap-4"><span><strong className="text-white">{active.length}</strong> đang chờ/đang chạy</span><span><strong className="text-emerald-400">{verified}</strong> đã xác minh</span><span className="flex items-center gap-1.5"><HardDrive size={13} />{diskAvailable ? 'Ghi đĩa · Có thể phục hồi' : 'Tệp ≤64 MiB · Bộ nhớ tạm'}</span>{backgroundConnections > 0 && <span>{backgroundConnections} kết nối nền</span>}</div>
      <div className="flex gap-1 rounded-lg bg-slate-950 p-1">{(['active', 'all'] as const).map(value => <button key={value} onClick={() => setFilter(value)} className={`rounded-md px-3 py-1.5 ${filter === value ? 'bg-slate-800 text-white' : 'text-slate-500 hover:text-slate-300'}`}>{value === 'active' ? 'Đang chạy' : 'Tất cả'}</button>)}</div>
    </div>
    {error && <p role="alert" className="border-t border-rose-400/20 bg-rose-400/10 px-5 py-3 text-xs text-rose-300">{error}</p>}
    {!diskAvailable && <p className="px-5 pt-4 text-xs text-amber-200/80">Dùng HTTPS trên Chrome/Edge để nhận tệp lớn và tiếp tục sau khi tải lại trang.</p>}
    {!displayed.length ? <div className="flex flex-col items-center gap-2 px-6 py-9 text-center"><div className="mb-1 rounded-2xl bg-slate-800/40 p-3 text-slate-500"><FolderDown size={27} /></div><p className="text-sm font-medium text-slate-300">{filter === 'active' ? 'Hàng đợi đang trống' : 'Chưa có lượt truyền'}</p><p className="max-w-sm text-xs leading-relaxed text-slate-500">Chọn thiết bị, thêm tệp và chờ người nhận đồng ý. Hoàn tất khi nội dung đã được kiểm tra và lưu.</p></div> :
      <div className="max-h-[480px] divide-y divide-slate-800/80 overflow-y-auto">{displayed.map(task => {
        const progress = task.size ? Math.min(100, Math.floor(task.offset / task.size * 100)) : task.status === 'completed' ? 100 : 0;
        const running = ['preparing', 'waiting', 'transferring', 'verifying'].includes(task.status);
        return <article key={task.id} className="px-5 py-4 sm:px-6">
          <div className="flex items-start gap-3"><div className={`mt-1 rounded-lg p-2.5 ${task.verified ? 'bg-emerald-400/10 text-emerald-400' : 'bg-slate-800 text-slate-400'}`}>{task.verified ? <ShieldCheck size={20} /> : <FileText size={20} />}</div>
            <div className="min-w-0 flex-1"><div className="flex flex-wrap items-center justify-between gap-2"><p title={task.name} className="max-w-full truncate text-sm font-semibold text-slate-100">{task.name}</p><span className={`flex items-center gap-1 text-[10px] ${task.verified ? 'text-emerald-400' : 'text-slate-400'}`}>{task.direction === 'sending' ? <ArrowUpRight size={13} /> : <ArrowDownLeft size={13} />}{task.direction === 'sending' ? 'Gửi' : 'Nhận'} · {statusLabels[task.status]}</span></div>
              <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-800"><div className={`h-full rounded-full transition-[width] duration-300 ${task.verified ? 'bg-emerald-400' : task.status === 'paused' ? 'bg-amber-400' : 'bg-gradient-to-r from-cyan-400 to-blue-500'}`} style={{ width: `${progress}%` }} /></div>
              <div className="mt-2 flex flex-wrap justify-between gap-2 text-[10px] text-slate-500"><span>{formatBytes(task.offset)} / {formatBytes(task.size)}{task.speed && task.status === 'transferring' ? ` · ${formatSpeed(task.speed)} · Còn khoảng ${formatETA((task.size - task.offset) / task.speed)}` : ''}</span><span>{task.status === 'verifying' ? 'Kiểm tra SHA-256 và lưu tệp…' : `${progress}%`}</span></div>
              {task.error && !['completed', 'cancelled'].includes(task.status) && <p className="mt-2 text-xs text-amber-300">{task.error}</p>}
              {task.hash && task.verified && <p title={task.hash} className="mt-2 font-mono text-[9px] text-emerald-500/80">SHA-256 {task.hash.slice(0, 16)}… · {task.savedTo === 'disk' ? 'Đã lưu vào thư mục đã chọn' : task.savedTo === 'browser' ? 'Đã lưu trong trình duyệt' : 'Đã xác minh trong bộ nhớ'}</p>}
              <div className="mt-3 flex flex-wrap gap-2">
                {task.status === 'completed' && task.direction === 'receiving' && <button onClick={() => void run(task.id, () => downloadFile(task.id))} className="rounded-lg bg-emerald-400/10 px-3 py-1.5 text-xs text-emerald-300">Tải tệp đã xác minh</button>}
                {task.status === 'offered' && <><button disabled={busy.has(task.id)} onClick={() => {
                  // Open the picker directly in the user's gesture, before any asynchronous storage work.
                  const picker = (window as unknown as { showSaveFilePicker?: SavePicker }).showSaveFilePicker;
                  const chosen = picker ? picker({ suggestedName: task.name }) : Promise.resolve(undefined);
                  void run(task.id, async () => acceptFile(task.id, await chosen));
                }} className="flex items-center gap-1.5 rounded-lg bg-emerald-400 px-3 py-1.5 text-xs font-semibold text-slate-950"><Check size={13} />Nhận và lưu</button><button onClick={() => void run(task.id, () => rejectFile(task.id))} className="rounded-lg bg-slate-800 px-3 py-1.5 text-xs text-slate-300">Từ chối</button></>}
                {running && task.direction === 'sending' && task.status !== 'verifying' && <button onClick={() => void run(task.id, () => pauseFile(task.id))} className="flex items-center gap-1.5 rounded-lg bg-slate-800 px-3 py-1.5 text-xs text-slate-300"><Pause size={12} />Tạm dừng</button>}
                {['paused', 'interrupted', 'failed'].includes(task.status) && task.direction === 'sending' && <button disabled={busy.has(task.id)} onClick={() => { resumeId.current = task.id; resumeInput.current?.click(); }} className="flex items-center gap-1.5 rounded-lg bg-cyan-400/10 px-3 py-1.5 text-xs text-cyan-300"><Play size={12} />Chọn tệp gốc và tiếp tục</button>}
                {['paused', 'interrupted', 'failed'].includes(task.status) && task.direction === 'receiving' && <span className="text-[10px] text-slate-500">Checkpoint được giữ. Chờ người gửi kết nối và gửi lại yêu cầu.</span>}
                {!['completed', 'cancelled', 'rejected'].includes(task.status) && <button onClick={() => void run(task.id, () => cancelFile(task.id))} className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs text-slate-500 hover:bg-rose-400/10 hover:text-rose-300"><X size={12} />Hủy tệp</button>}
                {['completed', 'cancelled', 'rejected', 'failed', 'interrupted'].includes(task.status) && <button title="Xóa lịch sử và dữ liệu tạm của tệp này" onClick={() => void run(task.id, () => removeFile(task.id))} className="ml-auto rounded-lg p-1.5 text-slate-600 hover:bg-slate-800 hover:text-slate-300"><Trash2 size={13} /></button>}
                {busy.has(task.id) && <Loader2 className="animate-spin text-cyan-400" size={14} />}
              </div>
            </div></div>
        </article>;
      })}</div>}
  </section>;
}
