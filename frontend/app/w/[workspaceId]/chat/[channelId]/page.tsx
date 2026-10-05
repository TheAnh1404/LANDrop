'use client';

import React, { useState, useRef, useEffect } from 'react';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Avatar } from '@/components/ui/avatar';
import { Button, IconButton } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { FileIcon } from '@/components/ui/file-icon';
import { useAuth } from '@/lib/auth/auth-context';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { MOCK_CHANNELS, MOCK_MESSAGES, MOCK_MEMBERS } from '@/lib/mock-data';
import { Message, MessageAttachment } from '@/lib/types';
import { formatRelativeTime, formatBytes } from '@/lib/utils';
import {
  Hash,
  Send,
  Paperclip,
  Smile,
  Reply,
  Edit2,
  Trash2,
  Copy,
  Check,
  X,
  Info,
  Zap,
  Users,
  ShieldCheck,
  CornerDownRight,
} from 'lucide-react';

export default function ChannelChatPage({
  params,
}: {
  params: { workspaceId: string; channelId: string };
}) {
  const { user } = useAuth();
  const { openSendModal } = useTransfer();

  const channels = MOCK_CHANNELS[params.workspaceId] || MOCK_CHANNELS.ws_dev || [];
  const currentChannel =
    channels.find((c) => c.id === params.channelId) || channels[0] || {
      id: params.channelId,
      name: 'general',
      topic: 'Team updates and announcements',
    };

  const initialMessages =
    MOCK_MESSAGES[params.channelId] || MOCK_MESSAGES.c_general || [];

  const [messages, setMessages] = useState<Message[]>(initialMessages);
  const [inputText, setInputText] = useState('');
  const [replyingTo, setReplyingTo] = useState<Message | null>(null);
  const [editingMessageId, setEditingMessageId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [isDetailsOpen, setIsDetailsOpen] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const handleSendMessage = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputText.trim()) return;

    const newMessage: Message = {
      id: `msg_${Date.now()}`,
      channelId: currentChannel.id,
      senderId: user?.id || 'usr_theanh',
      senderName: user?.name || 'The Anh',
      content: inputText.trim(),
      createdAt: new Date().toISOString(),
      replyToId: replyingTo?.id,
      replyTo: replyingTo
        ? {
            id: replyingTo.id,
            senderName: replyingTo.senderName,
            content: replyingTo.content,
          }
        : undefined,
    };

    setMessages((prev) => [...prev, newMessage]);
    setInputText('');
    setReplyingTo(null);
  };

  const handleStartEdit = (msg: Message) => {
    setEditingMessageId(msg.id);
    setEditText(msg.content);
  };

  const handleSaveEdit = (msgId: string) => {
    if (!editText.trim()) return;
    setMessages((prev) =>
      prev.map((m) =>
        m.id === msgId
          ? {
              ...m,
              content: editText.trim(),
              isEdited: true,
              updatedAt: new Date().toISOString(),
            }
          : m
      )
    );
    setEditingMessageId(null);
    setEditText('');
  };

  const handleDeleteMessage = (msgId: string) => {
    setMessages((prev) =>
      prev.map((m) =>
        m.id === msgId
          ? {
              ...m,
              content: 'This message was deleted.',
              isDeleted: true,
            }
          : m
      )
    );
  };

  const handleCopyMessage = (msg: Message) => {
    navigator.clipboard.writeText(msg.content);
    setCopiedId(msg.id);
    setTimeout(() => setCopiedId(null), 1500);
  };

  const handleReaction = (msgId: string, emoji: string) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== msgId) return m;
        const reactions = m.reactions ? [...m.reactions] : [];
        const existing = reactions.find((r) => r.emoji === emoji);
        if (existing) {
          existing.count += 1;
        } else {
          reactions.push({ emoji, count: 1, users: [user?.id || 'usr_cur'] });
        }
        return { ...m, reactions };
      })
    );
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      {/* Top Bar with Channel Title & Details Toggle */}
      <WorkspaceTopBar
        title={`#${currentChannel.name}`}
        subtitle={currentChannel.topic}
        actions={
          <IconButton
            variant="ghost"
            size="sm"
            aria-label="Channel Details"
            onClick={() => setIsDetailsOpen(!isDetailsOpen)}
            className={isDetailsOpen ? 'bg-slate-100 text-slate-900' : ''}
            title="Channel Info & Members"
          >
            <Info className="w-4 h-4" />
          </IconButton>
        }
      />

      {/* Main Chat Body & Optional Details Drawer */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* Message Feed Container */}
        <div className="flex-1 flex flex-col min-w-0 h-full">
          {/* Messages Scroll Area */}
          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
            {/* Channel Welcome Banner */}
            <div className="py-6 border-b border-slate-100 mb-2">
              <div className="w-10 h-10 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 font-bold mb-2 shadow-subtle">
                <Hash className="w-5 h-5" />
              </div>
              <h2 className="text-base font-bold text-slate-900">
                Welcome to #{currentChannel.name}!
              </h2>
              <p className="text-xs text-slate-500 mt-1 max-w-lg leading-relaxed">
                This is the official channel for #{currentChannel.name}. Real-time team messages and
                direct browser-to-browser P2P file transfers are synchronized here.
              </p>
            </div>

            {/* Date Separator Pill */}
            <div className="relative flex items-center justify-center my-4">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-slate-100" />
              </div>
              <div className="relative bg-white px-3 py-0.5 rounded-full border border-slate-200 text-[10px] font-semibold text-slate-400 select-none shadow-subtle">
                Today
              </div>
            </div>

            {/* Message Stream */}
            {messages.map((msg) => {
              const isOwnMessage = msg.senderId === user?.id || msg.senderId === 'usr_theanh';
              const isEditing = editingMessageId === msg.id;

              return (
                <div
                  key={msg.id}
                  className="group relative flex items-start gap-3 p-2 -mx-2 rounded-xl hover:bg-slate-50/80 transition-colors"
                >
                  <Avatar name={msg.senderName} size="md" className="mt-0.5" />

                  <div className="flex-1 min-w-0">
                    {/* Header */}
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-bold text-slate-900">{msg.senderName}</span>
                      <span className="text-[11px] text-slate-400">
                        {formatRelativeTime(msg.createdAt)}
                      </span>
                      {msg.isEdited && (
                        <span className="text-[10px] text-slate-400 italic">(edited)</span>
                      )}
                    </div>

                    {/* Reply quote preview */}
                    {msg.replyTo && (
                      <div className="mt-1 mb-1.5 pl-2.5 border-l-2 border-indigo-400 bg-slate-50 py-1 pr-2 rounded-r text-[11px] text-slate-600 flex items-center gap-1.5 max-w-lg">
                        <CornerDownRight className="w-3 h-3 text-indigo-500 flex-shrink-0" />
                        <span className="font-semibold text-slate-800">{msg.replyTo.senderName}:</span>
                        <span className="truncate">{msg.replyTo.content}</span>
                      </div>
                    )}

                    {/* Content or Edit Form */}
                    {isEditing ? (
                      <div className="mt-2 space-y-2">
                        <textarea
                          value={editText}
                          onChange={(e) => setEditText(e.target.value)}
                          className="w-full text-xs p-2.5 rounded-lg border border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500 bg-white"
                          rows={2}
                          autoFocus
                        />
                        <div className="flex items-center gap-2">
                          <Button variant="primary" size="xs" onClick={() => handleSaveEdit(msg.id)}>
                            Save
                          </Button>
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={() => {
                              setEditingMessageId(null);
                              setEditText('');
                            }}
                          >
                            Cancel
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <p
                        className={`text-xs mt-1 leading-relaxed ${
                          msg.isDeleted ? 'text-slate-400 italic' : 'text-slate-800'
                        }`}
                      >
                        {msg.content}
                      </p>
                    )}

                    {/* File Attachment Card */}
                    {msg.attachments && msg.attachments.length > 0 && (
                      <div className="mt-2.5 space-y-2">
                        {msg.attachments.map((att) => (
                          <div
                            key={att.id}
                            className="inline-flex flex-col sm:flex-row sm:items-center justify-between gap-3.5 p-3.5 bg-slate-50/80 border border-slate-200 rounded-xl shadow-subtle max-w-md w-full hover:border-slate-300 transition-colors"
                          >
                            <div className="flex items-center gap-3 min-w-0">
                              <div className="w-10 h-10 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center flex-shrink-0">
                                <FileIcon type={att.fileType} className="w-5 h-5 text-indigo-600" />
                              </div>
                              <div className="min-w-0">
                                <div className="text-xs font-semibold text-slate-900 truncate" title={att.fileName}>
                                  {att.fileName}
                                </div>
                                <div className="text-[11px] text-slate-500 flex items-center gap-2 mt-0.5 font-mono">
                                  <span>{formatBytes(att.fileSize)}</span>
                                  <span>•</span>
                                  <span className="text-teal-700 font-sans font-medium flex items-center gap-0.5">
                                    <Zap className="w-2.5 h-2.5 text-teal-600" /> Direct P2P
                                  </span>
                                </div>
                              </div>
                            </div>

                            <Button
                              variant="primary"
                              size="xs"
                              onClick={() => openSendModal({ name: att.fileName, size: att.fileSize })}
                              leftIcon={<Zap className="w-3 h-3" />}
                              className="self-end sm:self-center"
                            >
                              Pull P2P
                            </Button>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Reactions */}
                    {msg.reactions && msg.reactions.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {msg.reactions.map((r, i) => (
                          <button
                            key={i}
                            type="button"
                            onClick={() => handleReaction(msg.id, r.emoji)}
                            className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-slate-100 hover:bg-slate-200 text-slate-700 border border-slate-200 transition-colors"
                          >
                            <span>{r.emoji}</span>
                            <span className="text-[10px] font-semibold">{r.count}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Hover Action Bar */}
                  {!msg.isDeleted && !isEditing && (
                    <div className="absolute right-3 top-2 hidden group-hover:flex items-center gap-0.5 p-1 bg-white border border-slate-200 shadow-dropdown rounded-lg">
                      <button
                        type="button"
                        onClick={() => handleReaction(msg.id, '👍')}
                        className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-slate-800 text-xs"
                        title="React thumbs up"
                      >
                        👍
                      </button>
                      <button
                        type="button"
                        onClick={() => handleReaction(msg.id, '🚀')}
                        className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-slate-800 text-xs"
                        title="React rocket"
                      >
                        🚀
                      </button>
                      <div className="w-px h-3 bg-slate-200 mx-0.5" />
                      <button
                        type="button"
                        onClick={() => handleCopyMessage(msg)}
                        className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-indigo-600 text-xs"
                        title="Copy text"
                      >
                        {copiedId === msg.id ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                      </button>
                      <button
                        type="button"
                        onClick={() => setReplyingTo(msg)}
                        className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-indigo-600 text-xs"
                        title="Reply to message"
                      >
                        <Reply className="w-3.5 h-3.5" />
                      </button>

                      {isOwnMessage && (
                        <>
                          <button
                            type="button"
                            onClick={() => handleStartEdit(msg)}
                            className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-indigo-600 text-xs"
                            title="Edit message"
                          >
                            <Edit2 className="w-3.5 h-3.5" />
                          </button>
                          <button
                            type="button"
                            onClick={() => handleDeleteMessage(msg.id)}
                            className="p-1 hover:bg-slate-100 rounded text-slate-500 hover:text-red-600 text-xs"
                            title="Delete message"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
            <div ref={messagesEndRef} />
          </div>

          {/* Composer Box */}
          <div className="p-4 border-t border-slate-200 bg-white">
            {/* Replying banner */}
            {replyingTo && (
              <div className="mb-2 flex items-center justify-between p-2 bg-indigo-50 border border-indigo-200/80 rounded-lg text-xs text-indigo-900 animate-in fade-in duration-150">
                <div className="flex items-center gap-2 truncate">
                  <Reply className="w-3.5 h-3.5 text-indigo-600 flex-shrink-0" />
                  <span>
                    Replying to <strong>{replyingTo.senderName}</strong>: &quot;
                    {replyingTo.content.substring(0, 60)}...&quot;
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => setReplyingTo(null)}
                  className="p-1 text-indigo-400 hover:text-indigo-700"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>
            )}

            <form onSubmit={handleSendMessage} className="space-y-1.5">
              <div className="relative border border-slate-300 rounded-xl focus-within:border-indigo-500 focus-within:ring-1 focus-within:ring-indigo-500 bg-white transition-all shadow-subtle">
                <textarea
                  value={inputText}
                  onChange={(e) => setInputText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleSendMessage(e);
                    }
                  }}
                  placeholder={`Message #${currentChannel.name}...`}
                  rows={2}
                  className="w-full p-3 text-xs text-slate-900 placeholder:text-slate-400 focus:outline-none resize-none bg-transparent"
                />

                <div className="flex items-center justify-between p-2 pt-0 border-t border-slate-100">
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => openSendModal()}
                      className="p-1.5 text-slate-400 hover:text-indigo-600 hover:bg-slate-100 rounded-lg transition-colors"
                      title="Attach file via direct P2P"
                    >
                      <Paperclip className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setInputText((prev) => prev + ' 🚀 ')}
                      className="p-1.5 text-slate-400 hover:text-indigo-600 hover:bg-slate-100 rounded-lg transition-colors"
                      title="Insert emoji"
                    >
                      <Smile className="w-4 h-4" />
                    </button>
                  </div>

                  <div className="flex items-center gap-3">
                    <span className="hidden sm:inline text-[10px] text-slate-400 select-none">
                      Return to send • Shift + Return for new line
                    </span>
                    <Button
                      type="submit"
                      variant="primary"
                      size="xs"
                      disabled={!inputText.trim()}
                      rightIcon={<Send className="w-3 h-3" />}
                    >
                      Send
                    </Button>
                  </div>
                </div>
              </div>
            </form>
          </div>
        </div>

        {/* Optional Right Details Drawer */}
        {isDetailsOpen && (
          <aside className="w-72 border-l border-slate-200 bg-slate-50/60 p-5 overflow-y-auto space-y-5 animate-in slide-in-from-right-10 duration-200 flex-shrink-0">
            <div className="flex items-center justify-between pb-3 border-b border-slate-200">
              <h3 className="text-xs font-semibold text-slate-900 uppercase tracking-wider">
                Channel Details
              </h3>
              <button
                type="button"
                onClick={() => setIsDetailsOpen(false)}
                className="p-1 text-slate-400 hover:text-slate-600"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div>
              <div className="text-xs font-semibold text-slate-700">About #{currentChannel.name}</div>
              <p className="text-xs text-slate-500 mt-1 leading-relaxed">
                {currentChannel.topic || 'No topic set for this channel.'}
              </p>
            </div>

            <div>
              <div className="text-xs font-semibold text-slate-700 mb-2.5 flex items-center justify-between">
                <span>Active in channel</span>
                <span className="text-[10px] text-slate-400">4 members</span>
              </div>
              <div className="space-y-2.5">
                {(MOCK_MEMBERS[params.workspaceId] || MOCK_MEMBERS.ws_dev).map((m) => (
                  <div key={m.id} className="flex items-center justify-between text-xs">
                    <div className="flex items-center gap-2">
                      <Avatar name={m.name} size="xs" status={m.status} />
                      <span className="font-medium text-slate-700">{m.name}</span>
                    </div>
                    <span className="text-[10px] text-slate-400 capitalize">{m.role.toLowerCase()}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="p-3 bg-white border border-slate-200 rounded-xl space-y-1.5">
              <div className="flex items-center gap-1.5 text-xs font-semibold text-teal-800">
                <Zap className="w-3.5 h-3.5 text-teal-600" />
                <span>P2P DataChannel Active</span>
              </div>
              <p className="text-[11px] text-slate-500 leading-relaxed">
                Files sent in this channel stream directly peer-to-peer between browser sessions.
              </p>
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}
