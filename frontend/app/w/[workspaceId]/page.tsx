import { OverviewScreen } from '@/components/screens/overview';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <OverviewScreen id={params.workspaceId} />;
}
