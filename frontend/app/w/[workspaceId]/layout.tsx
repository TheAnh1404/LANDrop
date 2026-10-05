import React from 'react';
import { WorkspaceSidebar } from '@/components/layout/workspace-sidebar';

export default function WorkspaceLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: { workspaceId: string };
}) {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-slate-50 font-sans">
      {/* Persistent Workspace Sidebar */}
      <WorkspaceSidebar workspaceId={params.workspaceId} />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 h-full overflow-hidden bg-white">
        {children}
      </div>
    </div>
  );
}
