'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';

export default function RootPage() {
  const router = useRouter();
  const { isAuthenticated, serverInfo } = useAuth();

  useEffect(() => {
    if (!serverInfo || serverInfo.status === 'error') {
      router.replace('/connect');
    } else if (isAuthenticated) {
      router.replace('/w/ws_dev');
    } else {
      router.replace('/auth/login');
    }
  }, [isAuthenticated, serverInfo, router]);

  return (
    <div className="min-h-screen bg-slate-900 flex flex-col items-center justify-center text-white">
      <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-lg mb-4 shadow-panel animate-pulse">
        L
      </div>
      <div className="text-sm font-semibold tracking-wide">Connecting to LANDrop...</div>
      <div className="text-xs text-slate-400 mt-1">Initializing browser workspace & P2P runtime</div>
    </div>
  );
}
