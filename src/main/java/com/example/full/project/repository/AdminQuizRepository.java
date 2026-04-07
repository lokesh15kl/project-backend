package com.example.full.project.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.full.project.entity.AdminQuiz;

public interface AdminQuizRepository extends MongoRepository<AdminQuiz, String> {
    boolean existsByCategoryIgnoreCaseAndQuizTitleIgnoreCase(String category, String quizTitle);

    List<AdminQuiz> findByCategoryIgnoreCaseOrderByQuizTitleAsc(String category);

    void deleteByCategoryIgnoreCase(String category);
}
