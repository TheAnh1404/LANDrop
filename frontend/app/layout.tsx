import type { Metadata } from 'next';
import './globals.css';
import { AuthProvider } from '@/lib/auth/auth-context';
import { TransferProvider } from '@/lib/transfer/transfer-context';
import { GlobalTransferHud } from '@/components/layout/global-transfer-hud';
import { IncomingTransferDialog } from '@/components/layout/incoming-transfer-dialog';
import { SendFileModal } from '@/components/layout/send-file-modal';

export const metadata: Metadata = {
  title: 'LANDrop — Collaborative Workspace & Direct P2P Transfer',
  description:
    'Browser-based collaborative workspace combining team communication, file management, and direct WebRTC peer-to-peer file transfer.',
  icons: {
    icon: '/favicon.ico',
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="h-full">
      <body className="h-full antialiased font-sans bg-slate-50 text-slate-900 overflow-x-hidden">
        <AuthProvider>
          <TransferProvider>
            {children}
            <GlobalTransferHud />
            <IncomingTransferDialog />
            <SendFileModal />
          </TransferProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
