package com.example.full.project.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.full.project.entity.AssessmentCategory;

public interface AssessmentCategoryRepository extends MongoRepository<AssessmentCategory, String> {
    boolean existsByNameIgnoreCase(String name);

    Optional<AssessmentCategory> findByNameIgnoreCase(String name);

    void deleteByNameIgnoreCase(String name);
}
