package com.example.full.project.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.full.project.entity.ManualQuizQuestion;

public interface ManualQuizQuestionRepository extends MongoRepository<ManualQuizQuestion, String> {
    List<ManualQuizQuestion> findByCategoryIgnoreCaseAndQuizTitleIgnoreCaseOrderByIdAsc(String category, String quizTitle);

    void deleteByCategoryIgnoreCase(String category);

    void deleteByCategoryIgnoreCaseAndQuizTitleIgnoreCase(String category, String quizTitle);
}
