import type { ReactNode } from 'react';
import { AuthGate, WorkspaceShell } from '@/components/system/app-shell';
import { WorkspaceProvider } from '@/lib/data/workspace-provider';

export default function Layout({ children, params }: { children: ReactNode; params: { workspaceId: string } }) {
  return <AuthGate><WorkspaceProvider id={params.workspaceId}><WorkspaceShell id={params.workspaceId}>{children}</WorkspaceShell></WorkspaceProvider></AuthGate>;
}
