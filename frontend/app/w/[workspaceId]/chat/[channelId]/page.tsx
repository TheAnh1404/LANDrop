import { ChatScreen } from '@/components/screens/chat';

export default function Page({ params }: { params: { workspaceId: string; channelId: string } }) {
  return <ChatScreen id={params.workspaceId} channelId={params.channelId} />;
}
