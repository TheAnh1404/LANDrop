import { SettingsScreen } from '@/components/screens/settings';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <SettingsScreen id={params.workspaceId} />;
}
