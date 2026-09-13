package com.landrop.ui.cloud;

import com.landrop.ui.cloud.api.CloudApi;
import com.landrop.ui.cloud.api.PageRequest;

import java.io.PrintStream;

/** Opt-in console inspection of development data; no JavaFX screens or LAN state are changed. */
public final class CloudDataExample {
    private CloudDataExample() { }

    public static void main(String[] args) { renderMock(System.out); }

    public static void renderMock(PrintStream out) {
        CloudApi api = CloudApi.mock();
        PageRequest first = PageRequest.first();
        for (var workspace : api.workspaces().list(first).join().items()) {
            out.println("Workspace: " + workspace.name());
            for (var member : api.workspaces().members(workspace.id(), first).join().items()) {
                var user = api.users().get(member.userId()).join();
                out.println("Member: " + user.displayName() + " (" + member.role() + ")");
            }
            for (var channel : api.channels().list(workspace.id(), first).join().items()) {
                out.println("Channel: " + channel.name());
                for (var message : api.messages().list(channel.id(), first).join().items()) {
                    out.println("Message: " + message.type() + " " + message.content());
                }
            }
            for (var folder : api.folders().list(workspace.id(), first).join().items()) {
                out.println("Folder: " + folder.name());
            }
            for (var file : api.files().list(workspace.id(), first).join().items()) {
                out.println("File: " + file.name() + " (" + file.sizeBytes() + " bytes)");
            }
        }
    }
}
