package com.landrop.database.service;

import com.landrop.database.model.User;
import com.landrop.database.repository.UserRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserService() {
        this(new UserRepository());
    }

    public User createUser(String email, String username, String displayName, String avatarUrl) throws SQLException {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email cannot be empty.");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty.");
        }
        User user = new User(email.trim().toLowerCase(), username.trim(), displayName, avatarUrl);
        return userRepository.create(user);
    }

    public Optional<User> getUserById(UUID id) throws SQLException {
        return userRepository.findById(id);
    }

    public Optional<User> getUserByEmail(String email) throws SQLException {
        return userRepository.findByEmail(email);
    }

    public Optional<User> getUserByUsername(String username) throws SQLException {
        return userRepository.findByUsername(username);
    }

    public List<User> listUsers() throws SQLException {
        return userRepository.findAll();
    }

    public boolean deleteUser(UUID id) throws SQLException {
        return userRepository.deleteById(id);
    }
}
