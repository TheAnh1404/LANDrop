# LANDrop Database Foundation

> Scope: database foundation and existing Java JDBC services. Runtime schema evolution is applied by DatabaseCli using SQL files V1-V7 in backend/src/main/resources/db/migration/. Prisma is a schema reference in this checkout; no Prisma runtime dependency is configured. Check later migrations and prisma/schema.prisma for current fields.

This document specifies the architecture, relational schema, data access layer, migration protocol, and development setup for the **LANDrop Cloud Database Foundation** (Phase 14A).

---

## 1. Architectural Overview

Phase 14 introduces a persistent cloud PostgreSQL database hosted on **Neon** as the authoritative source of truth for persistent application data, without modifying or degrading the frozen LAN P2P Core (Phases 1–13).

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                        LANDrop Application Layer                        │
│                 (GUI Controllers / Peer Nodes / Clients)                │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          Service Layer (Java)                           │
│   (UserService, WorkspaceService, ChannelService, MessageService, etc.) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Repository Layer (Java)                         │
│  (UserRepository, WorkspaceRepository, ChannelRepository, FileRepo...)  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│            Connection Pool & Manager (HikariCP / JDBC Driver)           │
│        (DatabaseManager - Connection pooling, auto-reconnect, SSL)      │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ TLS 1.3 (TCP Port 5432)
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                  Neon Cloud Serverless PostgreSQL                       │
│        (Branching, auto-scaling compute, persistent storage)            │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Supported Entities (Phase 14 Baseline)

Phase 14 strictly establishes the 7 core entities:

1. **User**: Identity and account metadata.
2. **Workspace**: Collaboration and organization boundary.
3. **WorkspaceMember**: Many-to-many relationship linking Users to Workspaces with role permissions.
4. **Channel**: Real-time interactive messaging streams within a Workspace.
5. **Message**: Text communication, with self-referential threading support (`parent_message_id`).
6. **Folder**: Hierarchical folder structures within a Workspace.
7. **File**: Persistent file metadata (size, SHA-256 integrity hash, mime-type, cloud storage reference).

> [!NOTE]
> Out of scope for Phase 14: Tasks, Notifications, Activity Logs, Global Search Indexing, Payments, and AI Agents.

---

## 3. Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
    users ||--o{ workspaces : "owns"
    users ||--o{ workspace_members : "belongs to"
    users ||--o{ messages : "sends"
    users ||--o{ files : "uploads"

    workspaces ||--o{ workspace_members : "has"
    workspaces ||--o{ channels : "contains"
    workspaces ||--o{ folders : "contains"
    workspaces ||--o{ files : "stores"

    channels ||--o{ messages : "contains"
    messages ||--o{ messages : "replies to"

    folders ||--o{ folders : "parent of"
    folders ||--o{ files : "groups"

    users {
        uuid id PK
        varchar email UK
        varchar username UK
        varchar display_name
        text avatar_url
        timestamptz created_at
        timestamptz updated_at
    }

    workspaces {
        uuid id PK
        varchar name
        varchar slug UK
        text description
        uuid owner_id FK
        timestamptz created_at
        timestamptz updated_at
    }

    workspace_members {
        uuid id PK
        uuid workspace_id FK
        uuid user_id FK
        workspace_role role
        timestamptz joined_at
        timestamptz updated_at
    }

    channels {
        uuid id PK
        uuid workspace_id FK
        varchar name
        text topic
        boolean is_private
        timestamptz created_at
        timestamptz updated_at
    }

    messages {
        uuid id PK
        uuid channel_id FK
        uuid user_id FK
        uuid parent_message_id FK
        text content
        timestamptz created_at
        timestamptz updated_at
    }

    folders {
        uuid id PK
        uuid workspace_id FK
        varchar name
        uuid parent_folder_id FK
        timestamptz created_at
        timestamptz updated_at
    }

    files {
        uuid id PK
        uuid workspace_id FK
        uuid user_id FK
        uuid folder_id FK
        varchar name
        bigint size
        varchar mime_type
        varchar sha256
        text storage_key
        timestamptz created_at
        timestamptz updated_at
    }
```

---

## 4. Foreign Key Constraints & Cascading Rules

| Relationship | Foreign Key | Target Table | On Delete | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **Workspace Owner** | `workspaces.owner_id` | `users.id` | `RESTRICT` | Cannot delete a user while they own active workspaces. |
| **Workspace Membership** | `workspace_members.workspace_id` | `workspaces.id` | `CASCADE` | Removing a workspace removes all membership links. |
| **User Membership** | `workspace_members.user_id` | `users.id` | `CASCADE` | Removing a user purges their memberships. |
| **Channel Workspace** | `channels.workspace_id` | `workspaces.id` | `CASCADE` | Removing a workspace deletes its channels. |
| **Message Channel** | `messages.channel_id` | `channels.id` | `CASCADE` | Deleting a channel purges its messages. |
| **Message Author** | `messages.user_id` | `users.id` | `RESTRICT` | Preserves audit trail; messages must be explicitly handled. |
| **Thread Reply** | `messages.parent_message_id` | `messages.id` | `SET NULL` | Deleting a parent message preserves reply contents as unthreaded. |
| **Folder Workspace** | `folders.workspace_id` | `workspaces.id` | `CASCADE` | Deleting a workspace purges its directory hierarchy. |
| **Subfolder Parent** | `folders.parent_folder_id` | `folders.id` | `CASCADE` | Deleting a directory recursively deletes nested folders. |
| **File Workspace** | `files.workspace_id` | `workspaces.id` | `CASCADE` | Deleting a workspace removes its file records. |
| **File Uploader** | `files.user_id` | `users.id` | `RESTRICT` | Preserves file provenance and transfer history. |
| **File Folder** | `files.folder_id` | `folders.id` | `SET NULL` | Deleting a folder unlinks files to workspace root rather than deleting data. |

---

## 5. Naming Conventions

- **Database Tables**: Plural lowercase with underscores (`users`, `workspaces`, `workspace_members`).
- **Database Columns**: Lowercase snake_case (`display_name`, `owner_id`, `parent_folder_id`, `created_at`).
- **Java Entities**: PascalCase records/classes (`User`, `Workspace`, `WorkspaceMember`, `FileRecord`).
- **Java Fields**: camelCase matching column semantics (`displayName`, `ownerId`, `parentFolderId`, `createdAt`).
- **Identifiers**: 128-bit RFC 4122 UUIDs (`java.util.UUID` <-> PostgreSQL `UUID`).
- **Timestamps**: UTC `TIMESTAMPTZ` (<-> `java.time.Instant`).

---

## 6. Environment Configuration

LANDrop uses a hierarchical configuration system that never requires hardcoding credentials.

### Configuration Hierarchy
1. Java System Property: `-Dlandrop.database.url=postgresql://...`
2. Environment Variable: `DATABASE_URL=postgresql://...`
3. Local `.env` file (parsed automatically by `DatabaseConfig`).

### `.env.example` Template
```bash
# Neon PostgreSQL connection string (with SSL mode)
DATABASE_URL="postgresql://neondb_owner:PASSWORD@ep-example-pooler.region.aws.neon.tech/neondb?sslmode=require"
```

> [!CAUTION]
> Never commit `.env` to version control. The repository `.gitignore` is strictly configured to ignore all `.env` variants while preserving `.env.example`.

---

## 7. Migration & Seeding Instructions

### Running Migrations via Java CLI
```bash
# Execute DDL migration script against Neon PostgreSQL
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.landrop.database.DatabaseCli" -Dexec.args="migrate" -pl backend
```

### Running Deterministic Development Seed
```bash
# Seed development users, workspaces, channels, messages, folders, and files
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.landrop.database.DatabaseCli" -Dexec.args="seed" -pl backend
```

### Running Complete Verification Suite
```bash
# Run automated 10-check verification against Neon
.\mvnw.cmd test -Dtest=DatabaseIntegrationTest -pl backend
```
