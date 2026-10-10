'use client';

import React from 'react';
import Link from 'next/link';
import { ArrowRight, FolderOpen, Hash, Network, Radio, Users } from 'lucide-react';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { useWebRTC } from '@/lib/webrtc/webrtc-provider';
import { useDemoTransfers } from '@/lib/transfer/demo-provider';
import { Action, Empty, Feedback, FileRow, Heading, Initials, Panel, Stat, Tag } from '../system/primitives';

export function OverviewScreen({ id }: { id: string }) {
  const { workspace, channels, members, files, loading, error, refresh } = useWorkspace();
  const { user, mode } = useSession();
  const { onlinePeers } = useWebRTC();
  const { transfers } = useDemoTransfers();
  const base = `/w/${id}`;

  if (loading || !workspace) {
    return <Feedback loading={loading} error={error || 'Không tìm thấy workspace.'} retry={refresh} />;
  }

  const activePeersCount = mode === 'demo'
    ? transfers.filter(item => !['completed', 'cancelled', 'failed', 'declined'].includes(item.status)).length
    : onlinePeers.length;

  return (
    <>
      <Heading
        eyebrow="TỔNG QUAN KHÔNG GIAN"
        title={workspace.name}
        description={workspace.description || 'Trao đổi, tài liệu và những người cùng làm việc với bạn.'}
        action={
          <Link className="action action-primary" href={`${base}/files`}>
            <FolderOpen size={15} /> Mở kho tài liệu
          </Link>
        }
      />

      {error && <Feedback error={error} retry={refresh} />}

      <section className="hero">
        <div className="hero-copy">
          <p className="eyebrow">CHÀO MỪNG, {user?.name.toLocaleUpperCase()}</p>
          <h2>
            Một nhóm. Một không gian.
            <br />
            Sẵn sàng cho ý tưởng tiếp theo.
          </h2>
          <p>Bắt nhịp với cuộc thảo luận đang diễn ra, tìm tài liệu cần thiết hoặc kết nối với thành viên trong nhóm.</p>
          <div className="hero-actions">
            <Link className="action action-primary" href={`${base}/chat`}>
              Vào thảo luận <ArrowRight size={15} />
            </Link>
            <Link className="action action-secondary" href={`${base}/members`}>
              Xem thành viên
            </Link>
          </div>
        </div>
        <div className="hero-diagram">
          <span className="hero-node"><Hash size={26} /></span>
          <span className="hero-line" />
          <span className="hero-node hero-center"><Network size={35} /></span>
          <span className="hero-line" />
          <span className="hero-node"><FolderOpen size={26} /></span>
        </div>
      </section>

      <div className="stats-grid">
        <Stat
          label="Thành viên đã tải"
          value={members.length}
          icon={<Users size={16} />}
          detail="Tài khoản trong workspace"
        />
        <Stat
          label="Kênh thảo luận"
          value={channels.length}
          icon={<Hash size={16} />}
          detail="Kênh bạn được phép xem"
        />
        <Stat
          label="Tài liệu đã tải"
          value={files.length}
          icon={<FolderOpen size={16} />}
          detail="Metadata file của nhóm"
        />
        <Stat
          label="Thiết bị P2P trực tuyến"
          value={activePeersCount}
          icon={<Radio size={16} />}
          detail={mode === 'demo' ? 'Phiên mô phỏng' : `${activePeersCount} thiết bị trong mạng LAN`}
        />
      </div>

      <div className="split-grid">
        <Panel
          title="Tài liệu gần đây"
          description="Tệp được đăng ký trong không gian này"
          action={
            <Link href={`${base}/files`} className="action action-ghost">
              Xem tất cả <ArrowRight size={13} />
            </Link>
          }
        >
          {files.length ? (
            files.slice(0, 5).map(file => (
              <Link key={file.id} href={`${base}/files?file=${file.id}`}>
                <FileRow
                  name={file.name}
                  size={file.size}
                  detail={members.find(member => member.userId === file.ownerId)?.name || file.ownerName || 'Metadata trên server'}
                />
              </Link>
            ))
          ) : (
            <Empty title="Chưa có tài liệu" description="Thông tin file của nhóm sẽ xuất hiện ở đây." />
          )}
        </Panel>

        <div className="section-gap">
          <Panel
            title="Kênh của nhóm"
            action={
              <Link href={`${base}/chat`} className="action action-ghost">
                Mở thảo luận <ArrowRight size={13} />
              </Link>
            }
          >
            {channels.length ? (
              <div className="overview-links">
                {channels.slice(0, 4).map(channel => (
                  <Link className="overview-link" key={channel.id} href={`${base}/chat/${channel.id}`}>
                    <Hash size={19} />
                    <div>
                      <strong>{channel.name}</strong>
                      <p>{channel.topic || 'Trao đổi của nhóm'}</p>
                    </div>
                    <ArrowRight size={14} />
                  </Link>
                ))}
              </div>
            ) : (
              <Empty
                title="Chưa có kênh"
                action={
                  <Link className="action action-secondary" href={`${base}/chat`}>
                    Quản lý kênh
                  </Link>
                }
              />
            )}
          </Panel>

          <Panel
            title="Những người cùng làm việc"
            action={
              <Link className="action action-ghost" href={`${base}/members`}>
                Tất cả <ArrowRight size={13} />
              </Link>
            }
          >
            {members.slice(0, 5).map(member => {
              const isSelf = member.userId === user?.id;
              const activePeer = onlinePeers.find(p => p.userId === member.userId);
              return (
                <div className="list-row" key={member.id}>
                  <Initials name={member.name} />
                  <div className="row-content">
                    <strong>
                      {member.name}
                      {isSelf ? ' (bạn)' : ''}
                    </strong>
                    <span>
                      {member.role === 'OWNER' ? 'Chủ nhóm' : member.role === 'ADMIN' ? 'Quản trị nhóm' : 'Thành viên'}
                    </span>
                  </div>
                  {isSelf ? (
                    <Tag tone="green">Bạn (Online)</Tag>
                  ) : activePeer ? (
                    <Tag tone="green">Trực tuyến · {activePeer.deviceName || 'P2P'}</Tag>
                  ) : (
                    <Tag tone="neutral">{mode === 'demo' && member.status === 'online' ? 'Online mẫu' : 'Ngoại tuyến'}</Tag>
                  )}
                </div>
              );
            })}
          </Panel>
        </div>
      </div>
    </>
  );
}
