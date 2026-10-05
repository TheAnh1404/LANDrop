import { WorkspacesScreen } from '@/components/screens/workspaces';
import { AuthGate, WorkspacesShell } from '@/components/system/app-shell';

export default function Page() {
  return <AuthGate><WorkspacesShell><WorkspacesScreen /></WorkspacesShell></AuthGate>;
}
