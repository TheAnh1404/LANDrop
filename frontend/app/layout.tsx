import type { Metadata } from 'next';
import './globals.css';
import './theme.css';
import { SessionProvider } from '@/lib/session/session-provider';
import { RealtimeProvider } from '@/lib/realtime/realtime-provider';
import { DemoTransferProvider } from '@/lib/transfer/demo-provider';

export const metadata: Metadata = { title: 'LANDrop — Không gian cộng tác trong LAN', description: 'Trao đổi cùng nhóm, quản lý tài liệu và theo dõi truyền tệp trong mạng nội bộ.' };

export default function Layout({ children }: { children: React.ReactNode }) {
  return <html lang="vi"><body><a href="#main-content" className="screen-reader-link">Chuyển đến nội dung</a><SessionProvider><RealtimeProvider><DemoTransferProvider>{children}</DemoTransferProvider></RealtimeProvider></SessionProvider></body></html>;
}
