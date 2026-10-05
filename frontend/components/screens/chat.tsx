'use client';

import React, { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ArrowLeft, Hash, Lock, Plus, RefreshCw, Send } from 'lucide-react';
import { useWorkspace, listDemoMessages, saveDemoMessage, addDemoChannel } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { mapMessage, WireMessage } from '@/lib/api/models';
import { errorMessage } from '@/lib/api/client';
import { Channel, Message } from '@/lib/types';
import { Action, Dialog, Empty, Feedback, Field, Heading, Initials, Notice, Tag } from '../system/primitives';

export function ChannelsScreen({ id }: { id: string }) {
  const { channels, loading, error, workspace, refresh, mutate } = useWorkspace();
  const { mode } = useSession();
  const [create, setCreate] = useState(false);
  const [name, setName] = useState('');
  const [topic, setTopic] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const router = useRouter();
  const editable = workspace && ['OWNER', 'ADMIN'].includes(workspace.role);
  return <><Heading eyebrow="TRAO ĐỔI TRONG NHÓM" title="Kênh thảo luận" description="Mỗi chủ đề có một nơi riêng để trao đổi và lưu lại quyết định của nhóm." action={editable && <Action onClick={() => setCreate(true)}><Plus size={15} />Tạo kênh</Action>} /><Feedback loading={loading} error={error} retry={refresh} />{!loading && (channels.length ? <div className="channel-grid">{channels.map(channel => <Link href={`/w/${id}/chat/${channel.id}`} className="channel-card" key={channel.id}>{channel.isPrivate ? <Lock size={24} /> : <Hash size={24} />}<strong>{channel.name}</strong><p>{channel.topic || 'Không gian trao đổi của các thành viên trong nhóm.'}</p><Tag tone={channel.isPrivate ? 'amber' : 'green'}>{channel.isPrivate ? 'Kênh riêng' : 'Kênh nhóm'}</Tag></Link>)}</div> : <Empty icon={<Hash size={24} />} title="Nhóm chưa có kênh thảo luận" description="Chủ nhóm hoặc quản trị nhóm có thể tạo kênh để bắt đầu." />)}{create && <Dialog title="Tạo kênh thảo luận" description="Kênh công khai dành cho thành viên trong workspace." onClose={() => !busy && setCreate(false)}><form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); setFailure(''); try { if (mode === 'demo') { const channel = addDemoChannel(id, name.trim(), topic.trim()); refresh(); setCreate(false); router.push(`/w/${id}/chat/${channel.id}`); } else { await mutate(`/api/workspaces/${id}/channels`, 'POST', { name: name.trim(), topic: topic.trim() || null, isPrivate: false }); setCreate(false); } } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>{failure && <Notice danger>{failure}</Notice>}<Field label="Tên kênh" value={name} onChange={event => setName(event.target.value)} required maxLength={100} /><Field label="Chủ đề" value={topic} onChange={event => setTopic(event.target.value)} maxLength={2000} /><div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setCreate(false)}>Hủy</Action><Action type="submit" busy={busy} disabled={!name.trim()}>Tạo kênh</Action></div></form></Dialog>}</>;
}

export function ChatScreen({ id, channelId }: { id: string; channelId: string }) {
  const { channels, loading: workspaceLoading } = useWorkspace();
  const { mode, request, user } = useSession();
  const [messages, setMessages] = useState<Message[]>([]);
  const [channel, setChannel] = useState<Channel | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [revision, setRevision] = useState(0);
  const [cursor, setCursor] = useState<string | null>(null);
  const [older, setOlder] = useState(false);
  const [reply, setReply] = useState<Message | null>(null);
  const end = useRef<HTMLDivElement>(null);
  const current = useRef(channelId);
  const pending = useRef<{ text: string; replyId?: string; id: string } | null>(null);
  current.current = channelId;
  useEffect(() => {
    const controller = new AbortController();
    setError(''); setLoading(true); setDraft(''); setReply(null); setSending(false); setOlder(false); setChannel(null); setMessages([]); setCursor(null); pending.current = null;
    async function load() {
      try {
        if (mode === 'demo') { setChannel(channels.find(item => item.id === channelId) || null); setMessages(listDemoMessages(channelId)); setCursor(null); return; }
        const [channelResponse, history] = await Promise.all([request<{ data: Channel }>(`/api/channels/${channelId}`, { signal: controller.signal }), request<{ items: WireMessage[]; nextCursor: string | null }>(`/api/channels/${channelId}/messages?limit=50&order=desc`, { signal: controller.signal })]);
        if (controller.signal.aborted) return;
        if (channelResponse.data.workspaceId !== id) throw new Error('Kênh không thuộc workspace hiện tại.');
        setChannel(channelResponse.data); setMessages(history.items.map(mapMessage).sort((a, b) => a.createdAt.localeCompare(b.createdAt))); setCursor(history.nextCursor);
      } catch (failure) { if (!controller.signal.aborted) setError(errorMessage(failure)); }
      finally { if (!controller.signal.aborted) setLoading(false); }
    }
    if (!workspaceLoading) void load();
    return () => controller.abort();
  }, [channelId, id, mode, request, revision, workspaceLoading, channels]);
  useEffect(() => { if (!older) end.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length]);
  useEffect(() => {
    if (mode === 'demo' || !channel) return;
    let active = true;
    let busy = false;
    const controller = new AbortController();
    const timer = setInterval(async () => {
      if (busy || document.hidden) return;
      busy = true;
      try {
        const response = await request<{ items: WireMessage[] }>(`/api/channels/${channelId}/messages?limit=50&order=desc`, { signal: controller.signal });
        if (active) setMessages(previous => {
          const all = new Map(previous.map(item => [item.id, item]));
          response.items.map(mapMessage).forEach(item => all.set(item.id, item));
          return [...all.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
        });
      } catch (failure) { if (active) setError(errorMessage(failure)); }
      finally { busy = false; }
    }, 5000);
    return () => { active = false; controller.abort(); clearInterval(timer); };
  }, [mode, request, channelId, channel?.id]);
  const send = async () => {
    const text = draft.trim();
    if (!text || sending || !user) return;
    const currentChannel = channelId;
    setSending(true); setError('');
    if (!pending.current || pending.current.text !== text || pending.current.replyId !== reply?.id) pending.current = { text, replyId: reply?.id, id: crypto.randomUUID() };
    try {
      let message: Message;
      if (mode === 'demo') {
        message = { id: pending.current.id, channelId, content: text, createdAt: new Date().toISOString(), senderId: user.id, senderName: user.name, replyToId: reply?.id };
        saveDemoMessage(message);
      } else message = mapMessage((await request<{ data: WireMessage }>(`/api/channels/${channelId}/messages`, { method: 'POST', body: JSON.stringify({ content: text, clientMessageId: pending.current.id, ...(reply ? { replyToId: reply.id } : {}) }) })).data);
      if (current.current === currentChannel) { setMessages(previous => previous.some(item => item.id === message.id) ? previous : [...previous, message]); setDraft(''); setReply(null); pending.current = null; }
    } catch (failure) { if (current.current === currentChannel) setError(errorMessage(failure)); }
    finally { if (current.current === currentChannel) setSending(false); }
  };
  return <><div className="toolbar"><Link href={`/w/${id}/chat`} className="action action-ghost"><ArrowLeft size={14} />Các kênh</Link><Tag tone={mode === 'demo' ? 'amber' : 'green'}>{mode === 'demo' ? 'Tin nhắn mẫu' : 'Dữ liệu server · cập nhật mỗi 5 giây'}</Tag></div><div className="chat-layout"><div className="chat-heading"><div><strong># {channel?.name || 'Đang mở kênh…'}</strong><p>{channel?.topic || 'Trao đổi và ghi lại quyết định của nhóm'}</p></div><Action tone="ghost" onClick={() => setRevision(r => r + 1)} disabled={loading || sending}><RefreshCw size={15} />Làm mới</Action></div><div className="chat-timeline"><Feedback loading={loading} error={error} retry={() => setRevision(r => r + 1)} />{cursor && <Action tone="secondary" busy={older} onClick={async () => { const selectedChannel = channelId; setOlder(true); try { const history = await request<{ items: WireMessage[]; nextCursor: string | null }>(`/api/channels/${channelId}/messages?limit=50&cursor=${encodeURIComponent(cursor)}&order=desc`); if (current.current !== selectedChannel) return; setMessages(previous => { const all = new Map([...history.items.map(mapMessage), ...previous].map(item => [item.id, item])); return [...all.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt)); }); setCursor(history.nextCursor); } catch (error) { if (current.current === selectedChannel) setError(errorMessage(error)); } finally { if (current.current === selectedChannel) setOlder(false); } }}>Xem tin nhắn trước</Action>}{!loading && !messages.length && <Empty icon={<Hash size={23} />} title="Bắt đầu cuộc trò chuyện" description="Gửi lời chào đầu tiên tới nhóm." />}{messages.map(message => <article className="chat-message" key={message.id}><Initials name={message.senderName} /><div className="chat-content"><div className="chat-message-meta"><strong>{message.senderName}</strong><time>{new Date(message.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}</time>{message.isEdited && <Tag>Đã sửa</Tag>}</div>{message.replyToId && <p className="muted text-xs">↳ Trả lời một tin nhắn</p>}<p>{message.isDeleted ? 'Tin nhắn đã được xóa.' : message.content}</p>{!message.isDeleted && <div className="chat-message-actions"><button onClick={() => setReply(message)}>Trả lời</button></div>}</div></article>)}<div ref={end} /></div><div className="chat-composer">{reply && <div className="toolbar"><span className="text-xs muted">Trả lời {reply.senderName}: {reply.content.slice(0, 60)}</span><Action tone="ghost" onClick={() => setReply(null)}>Hủy</Action></div>}<form onSubmit={event => { event.preventDefault(); void send(); }}><textarea aria-label="Nội dung tin nhắn" placeholder={`Trao đổi trong #${channel?.name || 'kênh'}…`} value={draft} maxLength={20000} rows={2} disabled={sending || !channel || loading} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} /><Action type="submit" busy={sending} disabled={!draft.trim() || !channel || loading}><Send size={15} />Gửi</Action></form><small>Enter để gửi · Shift + Enter xuống dòng · File đính kèm cần metadata và transport đã xác thực.</small></div></div></>;
}
