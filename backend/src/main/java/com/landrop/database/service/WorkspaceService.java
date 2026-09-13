package com.landrop.database.service;

import com.landrop.database.model.Workspace;
import com.landrop.database.model.WorkspaceMember;
import com.landrop.database.model.WorkspaceRole;
import com.landrop.database.repository.WorkspaceRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public WorkspaceService() {
        this(new WorkspaceRepository());
    }

    public Workspace createWorkspace(String name, String slug, String description, UUID ownerId) throws SQLException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name cannot be empty.");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Workspace slug cannot be empty.");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Workspace ownerId is required.");
        }

        Workspace workspace = workspaceRepository.create(new Workspace(name.trim(), slug.trim().toLowerCase(), description, ownerId));
        // Add owner as member automatically with OWNER role
        workspaceRepository.addMember(new WorkspaceMember(workspace.id(), ownerId, WorkspaceRole.OWNER));
        return workspace;
    }

    public Optional<Workspace> getWorkspaceById(UUID id) throws SQLException {
        return workspaceRepository.findById(id);
    }

    public Optional<Workspace> getWorkspaceBySlug(String slug) throws SQLException {
        return workspaceRepository.findBySlug(slug);
    }

    public List<Workspace> listWorkspaces() throws SQLException {
        return workspaceRepository.findAll();
    }

    public List<Workspace> listUserWorkspaces(UUID userId) throws SQLException {
        return workspaceRepository.findByUserId(userId);
    }

    public WorkspaceMember addMember(UUID workspaceId, UUID userId, WorkspaceRole role) throws SQLException {
        return workspaceRepository.addMember(new WorkspaceMember(workspaceId, userId, role));
    }

    public List<WorkspaceMember> getMembers(UUID workspaceId) throws SQLException {
        return workspaceRepository.getMembers(workspaceId);
    }

    public boolean removeMember(UUID workspaceId, UUID userId) throws SQLException {
        return workspaceRepository.removeMember(workspaceId, userId);
    }

    public boolean deleteWorkspace(UUID id) throws SQLException {
        return workspaceRepository.deleteById(id);
    }
}
