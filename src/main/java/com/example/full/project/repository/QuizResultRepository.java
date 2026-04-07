package com.example.full.project.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.full.project.entity.QuizResult;

public interface QuizResultRepository extends MongoRepository<QuizResult, String> {
    List<QuizResult> findAllByOrderByAttemptedAtDesc();

    List<QuizResult> findByEmailIgnoreCaseOrderByAttemptedAtDesc(String email);
}
