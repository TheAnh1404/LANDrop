# LANDrop expansion implementation status

This document tracks the audit and expansion plan supplied on 2026-09-28. It describes behavior in this checkout, not a production certification.

## Available now

| Area | Result |
| --- | --- |
| Quick LAN Share | Choose the offline button on the server discovery/login screen, or start with `--offline=true`. The client opens without a server account or database. |
| LAN discovery | UDP multicast presence on `239.255.88.88:8889`; peers appear automatically, with stale peer eviction. The existing TCP registry remains optional in Workspace mode. |
| Direct messages | Chat has a LAN Direct Messages tab. Select a peer, send messages or files, and use the same screen alongside Workspace channels. |
| Folder sharing | Select **Gửi thư mục** in direct chat. The folder tree is streamed into a temporary ZIP and offered by the existing transfer engine. The receiver extracts the ZIP manually. |
| Clipboard snippets | **Gửi clipboard** sends the current text explicitly; each received message has a copy action. Clipboard contents are never broadcast automatically. |
| Image previews | Completed incoming PNG/JPEG/WebP files from the active peer appear as bounded thumbnails in direct chat when JavaFX can decode them. |
| Desktop tray | When supported, closing the window hides it in the tray. The menu opens the app, toggles automatic file acceptance, opens Downloads, and quits. Incoming/completed transfers show a native tray notification and play a system beep. |
| Windows portable build | `scripts/package-windows.ps1` builds an `app-image` with a bundled Java runtime. It accepts `-Type exe` or `-Type msi` on a Windows host with WiX Toolset installed. |
| Server containers | `Dockerfile` and `compose.yaml` define a server and PostgreSQL with persistent storage. Production startup requires a configured administrator password. |

## Run and verify

Build with Java 21 or newer and Maven 3.9+:

```powershell
mvn -pl frontend -am -DskipTests package
mvn -pl frontend -am "-Dtest=UIStateServiceTest,UdpDiscoveryServiceTest,FolderArchiveServiceTest,ProductionAdminPasswordTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Start two LAN clients after `mvn install`:

```powershell
mvn -pl frontend javafx:run "-Djavafx.args=--offline=true --peer-port=5001 --display-name=Alice"
mvn -pl frontend javafx:run "-Djavafx.args=--offline=true --peer-port=5002 --display-name=Bob"
```

Windows portable package:

```powershell
.\scripts\package-windows.ps1 -Type app-image
```

Use a dedicated test database for database integration tests. Ordinary `mvn test` can read the repository's `.env` and connect to its configured PostgreSQL instance.

For Docker Compose, set `POSTGRES_PASSWORD`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, and `ADMIN_PASSWORD` in your local shell or untracked `.env` before `docker compose up --build`. `ADMIN_PASSWORD` must contain at least 12 characters. The server applies its existing SQL migrations on startup.

## Work still needed before production

- P2P TCP chat and file transfer are still plaintext. Add authenticated TLS with certificate pinning or another verified identity and key exchange design before using untrusted LANs.
- The server REST/WebSocket endpoints still need HTTPS/WSS, rate limiting, and deployment security review.
- The dark theme is a first pass; some legacy inline colors in FXML and generated rows still need conversion to themeable CSS. System tray balloons do not offer action buttons.
- Audio/video previews, version update checks, and automatic cleanup of expired database sessions are not implemented.
- Folder transfers arrive as ZIP archives; automatic safe extraction, folder-level resume, and large folder acceptance tests remain.
- Installer `.exe`/`.msi`, Docker deployment, two-machine LAN testing, 1 GB pause/resume testing, and release signing have not been verified in this environment.
