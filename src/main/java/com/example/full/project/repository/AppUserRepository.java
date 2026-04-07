package com.example.full.project.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.full.project.entity.AppUser;

public interface AppUserRepository extends MongoRepository<AppUser, String> {
    List<AppUser> findAllByEmailIgnoreCaseOrderByIdAsc(String email);
    Optional<AppUser> findFirstByEmailIgnoreCaseOrderByIdAsc(String email);
    Optional<AppUser> findFirstByNameIgnoreCaseOrderByIdAsc(String name);
    boolean existsByEmailIgnoreCase(String email);
}
