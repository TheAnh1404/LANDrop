package com.landrop.ui.models;

/**
 * Top-level views available in the desktop application.
 */
public enum NavigationTab {
    DASHBOARD("Dashboard"),
    WORKSPACES("Workspaces"),
    PEERS("Peers"),
    CHAT("Chat"),
    TRANSFERS("Transfers"),
    SETTINGS("Settings");

    private final String title;

    NavigationTab(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
