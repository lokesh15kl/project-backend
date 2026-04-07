package com.example.full.project.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.full.project.entity.AppUser;
import com.example.full.project.repository.AppUserRepository;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_USER = "USER";

    private final AppUserRepository userRepository;

    public UserController(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<Map<String, Object>> getAllUsers() {
        return userRepository.findAll().stream().map(this::toSafeUser).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable String id) {
        return userRepository.findById(id)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(toSafeUser(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "user not found")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody AppUser payload) {
        AppUser existing = userRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "user not found"));
        }

        String name = payload.getName() == null ? "" : payload.getName().trim();
        String email = payload.getEmail() == null ? "" : payload.getEmail().trim().toLowerCase();
        String password = payload.getPassword() == null ? "" : payload.getPassword().trim();
        String role = payload.getRole() == null ? "" : payload.getRole().trim().toUpperCase();

        if (!name.isBlank()) {
            existing.setName(name);
        }

        if (!email.isBlank() && !email.equalsIgnoreCase(existing.getEmail())) {
            if (userRepository.existsByEmailIgnoreCase(email)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "email already exists"));
            }
            existing.setEmail(email);
        }

        if (!password.isBlank()) {
            existing.setPassword(password);
        }

        if (!role.isBlank()) {
            if (!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "role must be USER or ADMIN"));
            }
            existing.setRole(role);
        }

        userRepository.save(existing);
        return ResponseEntity.ok(toSafeUser(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable String id) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "user not found"));
        }
        userRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "user deleted"));
    }

    private Map<String, Object> toSafeUser(AppUser user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("role", user.getRole());
        response.put("createdAt", user.getCreatedAt());
        return response;
    }
}
