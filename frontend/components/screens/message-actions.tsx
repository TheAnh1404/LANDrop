'use client';

import { useState } from 'react';
import { useSession } from '@/lib/session/session-provider';
import { errorMessage } from '@/lib/api/client';
import { mapMessage, WireMessage } from '@/lib/api/models';
import { Message } from '@/lib/types';
import { Action, Dialog, Notice } from '../system/primitives';

export function MessageActions({ message, onReply, onUpdate }: {
  message: Message;
  onReply: () => void;
  onUpdate: (message: Message) => void;
}) {
  const { mode, user, request } = useSession();
  const [action, setAction] = useState<'edit' | 'delete' | null>(null);
  const [content, setContent] = useState(message.content);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const own = mode === 'live' && message.senderId === user?.id;

  async function submit() {
    setBusy(true); setError('');
    try {
      if (action === 'edit') {
        const response = await request<{ data: WireMessage }>(`/api/messages/${message.id}`, {
          method: 'PATCH', body: JSON.stringify({ content: content.trim() }),
        });
        onUpdate(mapMessage(response.data));
      } else {
        await request(`/api/messages/${message.id}`, { method: 'DELETE' });
        onUpdate({ ...message, content: '', isDeleted: true });
      }
      setAction(null);
    } catch (failure) { setError(errorMessage(failure)); }
    finally { setBusy(false); }
  }

  return <>
    <div className="chat-message-actions">
      <button onClick={onReply}>Trả lời</button>
      {own && <>
        <button onClick={() => { setContent(message.content); setError(''); setAction('edit'); }}>Sửa</button>
        <button onClick={() => { setError(''); setAction('delete'); }}>Xóa</button>
      </>}
    </div>
    {action && <Dialog title={action === 'edit' ? 'Sửa tin nhắn' : 'Xóa tin nhắn?'}
      description={action === 'delete' ? 'Nội dung sẽ bị xóa; dấu vết tin nhắn vẫn được giữ trong lịch sử.' : 'Chỉ người gửi có thể sửa nội dung tin nhắn.'}
      onClose={() => !busy && setAction(null)}>
      <form className="form-stack" onSubmit={event => { event.preventDefault(); void submit(); }}>
        {error && <Notice danger>{error}</Notice>}
        {action === 'edit' && <label className="field"><span>Nội dung</span>
          <textarea value={content} onChange={event => setContent(event.target.value)} required maxLength={4000} rows={5} disabled={busy} />
        </label>}
        <div className="form-actions">
          <Action tone="secondary" disabled={busy} onClick={() => setAction(null)}>Hủy</Action>
          <Action type="submit" tone={action === 'delete' ? 'danger' : 'primary'} busy={busy} disabled={action === 'edit' && !content.trim()}>
            {action === 'edit' ? 'Lưu thay đổi' : 'Xóa tin nhắn'}
          </Action>
        </div>
      </form>
    </Dialog>}
  </>;
}
