'use client';

import React, { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ArrowLeft, Hash, Lock, Plus, RefreshCw, Send } from 'lucide-react';
import { useWorkspace, listDemoMessages, saveDemoMessage, addDemoChannel } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { useRealtime } from '@/lib/realtime/realtime-provider';
import { mapMessage, WireMessage } from '@/lib/api/models';
import { reconcileMessages } from '@/lib/realtime/reconcile';
import { errorMessage } from '@/lib/api/client';
import { Channel, Message } from '@/lib/types';
import { Action, Dialog, Empty, Feedback, Field, Heading, Initials, Notice, Tag } from '../system/primitives';
import { MessageActions } from './message-actions';

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
  return <><Heading eyebrow="TRAO ĐỔI TRONG NHÓM" title="Kênh thảo luận" description="Mỗi chủ đề có một nơi riêng để trao đổi và lưu lại quyết định của nhóm." action={editable && <Action onClick={() => setCreate(true)}><Plus size={15} />Tạo kênh</Action>} /><Feedback loading={loading} error={error} retry={refresh} />{!loading && (channels.length ? <div className="channel-grid">{channels.map(channel => <Link href={`/w/${id}/chat/${channel.id}`} className="channel-card" key={channel.id}>{channel.isPrivate ? <Lock size={24} /> : <Hash size={24} />}<strong>{channel.name}</strong><p>{channel.topic || 'Không gian trao đổi của các thành viên trong nhóm.'}</p><Tag tone={channel.isPrivate ? 'amber' : 'green'}>{channel.isPrivate ? 'Kênh riêng' : 'Kênh nhóm'}</Tag></Link>)}</div> : <Empty icon={<Hash size={24} />} title="Nhóm chưa có kênh thảo luận" description="Chủ nhóm hoặc quản trị nhóm có thể tạo kênh để bắt đầu." />)}{create && <Dialog title="Tạo kênh thảo luận" description="Kênh công khai dành cho thành viên trong workspace." onClose={() => !busy && setCreate(false)}><form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); setFailure(''); try { if (mode === 'demo') { const channel = addDemoChannel(id, name.trim(), topic.trim()); refresh(); setCreate(false); router.push(`/w/${id}/chat/${channel.id}`); } else { await mutate(`/api/workspaces/${id}/channels`, 'POST', { name: name.trim(), topic: topic.trim() || null }); setCreate(false); } } catch (error) { setFailure(errorMessage(error)); } finally { setBusy(false); } }}>{failure && <Notice danger>{failure}</Notice>}<Field label="Tên kênh" value={name} onChange={event => setName(event.target.value)} required maxLength={100} /><Field label="Chủ đề" value={topic} onChange={event => setTopic(event.target.value)} maxLength={2000} /><div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setCreate(false)}>Hủy</Action><Action type="submit" busy={busy} disabled={!name.trim()}>Tạo kênh</Action></div></form></Dialog>}</>;
}

function generateClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function ChatScreen({ id, channelId }: { id: string; channelId: string }) {
  const { channels, loading: workspaceLoading } = useWorkspace();
  const { mode, request, user } = useSession();
  const { connectionState, client: realtime } = useRealtime();
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

  // Initial channel & history load
  useEffect(() => {
    const controller = new AbortController();
    setError(''); setLoading(true); setDraft(''); setReply(null); setSending(false); setOlder(false); setChannel(null); setMessages([]); setCursor(null); pending.current = null;
    async function load() {
      try {
        if (mode === 'demo') { setChannel(channels.find(item => item.id === channelId) || null); setMessages(listDemoMessages(channelId)); setCursor(null); return; }
        const [channelResponse, history] = await Promise.all([
          request<{ data: Channel }>(`/api/channels/${channelId}`, { signal: controller.signal }),
          request<{ items: WireMessage[]; nextCursor: string | null }>(`/api/channels/${channelId}/messages?limit=50&order=desc`, { signal: controller.signal })
        ]);
        if (controller.signal.aborted) return;
        if (channelResponse.data.workspaceId !== id) throw new Error('Kênh không thuộc workspace hiện tại.');
        setChannel(channelResponse.data);
        // Merge history with any live events received during fetch using monotonic reconciliation
        setMessages(previous => reconcileMessages(previous, history.items.map(mapMessage)));
        setCursor(history.nextCursor);
      } catch (failure) { if (!controller.signal.aborted) setError(errorMessage(failure)); }
      finally { if (!controller.signal.aborted) setLoading(false); }
    }
    if (!workspaceLoading) void load();
    return () => controller.abort();
  }, [channelId, id, mode, request, revision, workspaceLoading, channels]);

  useEffect(() => { if (!older) end.current?.scrollIntoView({ block: 'nearest' }); }, [messages.length]);

  // WebSocket Realtime subscription & event listener (Replaces 5-second polling)
  useEffect(() => {
    if (mode === 'demo' || !channel) return;

    // 1. Subscribe to channel events on WebSocket gateway
    realtime.subscribeChannel(channelId);

    // 2. Listen to realtime message broadcasts
    const unsubscribeMessage = realtime.onMessage(event => {
      if (current.current !== channelId) return;

      if (event.type === 'message.created') {
        if (event.payload.channelId !== channelId) return;
        const incoming = mapMessage(event.payload.message);
        setMessages(previous => reconcileMessages(previous, incoming));
      } else if (event.type === 'message.updated') {
        if (event.payload.channelId !== channelId) return;
        const updated = mapMessage(event.payload.message);
        setMessages(previous => reconcileMessages(previous, updated));
      } else if (event.type === 'message.deleted') {
        if (event.payload.channelId !== channelId) return;
        if (event.payload.message) {
          const mapped = mapMessage(event.payload.message);
          setMessages(previous => reconcileMessages(previous, {
            ...mapped,
            isDeleted: true,
            content: 'Tin nhắn đã được xóa.',
          }));
        } else {
          const msgId = event.payload.messageId || 'unknown';
          const deletedAt = event.payload.deletedAt || new Date().toISOString();
          setMessages(previous => {
            const target = previous.find(p => p.id === msgId);
            if (target) {
              return reconcileMessages(previous, {
                ...target,
                isDeleted: true,
                deletedAt,
                content: 'Tin nhắn đã được xóa.',
              });
            }
            return reconcileMessages(previous, {
              id: msgId,
              channelId,
              senderId: '',
              senderName: '',
              content: 'Tin nhắn đã được xóa.',
              createdAt: deletedAt,
              deletedAt,
              isDeleted: true,
            });
          });
        }
      }
    });

    // 3. Catch up on messages missed during offline reconnect once channel.joined is confirmed by server
    const unsubscribeChannelJoined = realtime.onChannelJoined(async joinedChannelId => {
      if (joinedChannelId !== channelId || current.current !== channelId) return;
      try {
        const catchUp = await request<{ items: WireMessage[] }>(`/api/channels/${channelId}/messages?limit=50&order=desc`);
        if (current.current !== channelId) return;
        setMessages(previous => reconcileMessages(previous, catchUp.items.map(mapMessage)));
      } catch {
        /* ignore background catch-up errors */
      }
    });

    return () => {
      realtime.unsubscribeChannel(channelId);
      unsubscribeMessage();
      unsubscribeChannelJoined();
    };
  }, [mode, channelId, channel?.id, realtime, request]);

  const send = async () => {
    const text = draft.trim();
    if (!text || sending || !user) return;
    const currentChannel = channelId;
    setSending(true); setError('');
    if (!pending.current || pending.current.text !== text || pending.current.replyId !== reply?.id) {
      pending.current = { text, replyId: reply?.id, id: generateClientMessageId() };
    }
    try {
      let message: Message;
      if (mode === 'demo') {
        message = { id: pending.current.id, channelId, content: text, createdAt: new Date().toISOString(), senderId: user.id, senderName: user.name, replyToId: reply?.id };
        saveDemoMessage(message);
      } else {
        const res = await request<{ data: WireMessage }>(`/api/channels/${channelId}/messages`, {
          method: 'POST',
          body: JSON.stringify({ content: text, clientMessageId: pending.current.id, ...(reply ? { replyToId: reply.id } : {}) })
        });
        message = mapMessage(res.data);
      }
      if (current.current === currentChannel) {
        setMessages(previous => reconcileMessages(previous, message));
        setDraft('');
        setReply(null);
        pending.current = null;
      }
    } catch (failure) {
      if (current.current === currentChannel) setError(errorMessage(failure));
    } finally {
      if (current.current === currentChannel) setSending(false);
    }
  };

  const renderConnectionTag = () => {
    if (mode === 'demo') return <Tag tone="amber">Tin nhắn mẫu</Tag>;
    switch (connectionState) {
      case 'CONNECTED':
        return <Tag tone="green">Realtime · Trực tuyến</Tag>;
      case 'RECONNECTING':
        return <Tag tone="amber">Realtime · Đang kết nối lại…</Tag>;
      case 'CONNECTING':
      case 'HANDSHAKING':
      case 'AUTHENTICATING':
        return <Tag tone="amber">Realtime · Đang thiết lập…</Tag>;
      case 'AUTH_REQUIRED':
      case 'SESSION_EXPIRED':
      case 'SESSION_REVOKED':
        return <Tag tone="red">Realtime · Phiên hết hạn</Tag>;
      case 'DISCONNECTED':
      default:
        return <Tag tone="neutral">Realtime · Mất kết nối</Tag>;
    }
  };

  return <><div className="toolbar"><Link href={`/w/${id}/chat`} className="action action-ghost"><ArrowLeft size={14} />Các kênh</Link>{renderConnectionTag()}</div><div className="chat-layout"><div className="chat-heading"><div><strong># {channel?.name || 'Đang mở kênh…'}</strong><p>{channel?.topic || 'Trao đổi và ghi lại quyết định của nhóm'}</p></div><Action tone="ghost" onClick={() => setRevision(r => r + 1)} disabled={loading || sending}><RefreshCw size={15} />Làm mới</Action></div><div className="chat-timeline"><Feedback loading={loading} error={error} retry={() => setRevision(r => r + 1)} />{cursor && <Action tone="secondary" busy={older} onClick={async () => { const selectedChannel = channelId; setOlder(true); try { const history = await request<{ items: WireMessage[]; nextCursor: string | null }>(`/api/channels/${channelId}/messages?limit=50&cursor=${encodeURIComponent(cursor)}&order=desc`); if (current.current !== selectedChannel) return; setMessages(previous => reconcileMessages(previous, history.items.map(mapMessage))); setCursor(history.nextCursor); } catch (error) { if (current.current === selectedChannel) setError(errorMessage(error)); } finally { if (current.current === selectedChannel) setOlder(false); } }}>Xem tin nhắn trước</Action>}{!loading && !messages.length && <Empty icon={<Hash size={23} />} title="Bắt đầu cuộc trò chuyện" description="Gửi lời chào đầu tiên tới nhóm." />}{messages.map(message => <article className="chat-message" key={message.id}><Initials name={message.senderName} /><div className="chat-content"><div className="chat-message-meta"><strong>{message.senderName}</strong><time>{new Date(message.createdAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}</time>{message.isEdited && <Tag>Đã sửa</Tag>}</div>{message.replyToId && <p className="muted text-xs">↳ Trả lời một tin nhắn</p>}<p>{message.isDeleted ? 'Tin nhắn đã được xóa.' : message.content}</p>{!message.isDeleted && <MessageActions message={message} onReply={() => setReply(message)} onUpdate={updated => setMessages(previous => reconcileMessages(previous, updated))} />}</div></article>)}<div ref={end} /></div><div className="chat-composer">{reply && <div className="toolbar"><span className="text-xs muted">Trả lời {reply.senderName}: {reply.content.slice(0, 60)}</span><Action tone="ghost" onClick={() => setReply(null)}>Hủy</Action></div>}<form onSubmit={event => { event.preventDefault(); void send(); }}><textarea aria-label="Nội dung tin nhắn" placeholder={`Trao đổi trong #${channel?.name || 'kênh'}…`} value={draft} maxLength={4000} rows={2} disabled={sending || !channel || loading} onChange={event => setDraft(event.target.value)} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} /><Action type="submit" busy={sending} disabled={!draft.trim() || !channel || loading}><Send size={15} />Gửi</Action></form><small>Enter để gửi · Shift + Enter xuống dòng · Tối đa 4.000 ký tự.</small></div></div></>;
}
