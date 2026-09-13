package com.landrop.ui.cloud.api;

import java.net.URI;

/** Opt-in persistent metadata client. Construction never starts LAN or cloud I/O. */
public final class CloudApi {
    private final UsersApi users;
    private final WorkspacesApi workspaces;
    private final ChannelsApi channels;
    private final MessagesApi messages;
    private final FilesApi files;
    private final FoldersApi folders;

    public CloudApi(ApiTransport transport) {
        RequestClient client = new RequestClient(transport);
        users = new UsersApi(client);
        workspaces = new WorkspacesApi(client);
        channels = new ChannelsApi(client);
        messages = new MessagesApi(client);
        files = new FilesApi(client);
        folders = new FoldersApi(client);
    }

    public static CloudApi mock() { return new CloudApi(new MockApiTransport()); }
    public static CloudApi http(URI apiBaseUri) { return new CloudApi(new HttpApiTransport(apiBaseUri)); }

    public UsersApi users() { return users; }
    public WorkspacesApi workspaces() { return workspaces; }
    public ChannelsApi channels() { return channels; }
    public MessagesApi messages() { return messages; }
    public FilesApi files() { return files; }
    public FoldersApi folders() { return folders; }
}
