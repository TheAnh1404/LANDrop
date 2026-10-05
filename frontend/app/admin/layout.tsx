import type { ReactNode } from 'react';
import { AdminShell, AuthGate } from '@/components/system/app-shell';

export default function Layout({ children }: { children: ReactNode }) {
  return <AuthGate admin><AdminShell>{children}</AdminShell></AuthGate>;
}
