import React from 'react';
import { AdminSidebar } from '@/components/layout/admin-sidebar';

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-slate-50 font-sans">
      {/* Persistent Admin Sidebar */}
      <AdminSidebar />

      {/* Main Admin Content */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden bg-white">
        {children}
      </div>
    </div>
  );
}
