import { ChannelsScreen } from '@/components/screens/chat';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <ChannelsScreen id={params.workspaceId} />;
}
