package com.example.full.project.service;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.full.project.entity.AdminQuiz;
import com.example.full.project.entity.AssessmentCategory;
import com.example.full.project.entity.ManualQuizQuestion;
import com.example.full.project.repository.AdminQuizRepository;
import com.example.full.project.repository.AssessmentCategoryRepository;
import com.example.full.project.repository.ManualQuizQuestionRepository;

@Component
public class DefaultAssignmentSeeder implements ApplicationRunner {

    private static final Logger LOGGER = Logger.getLogger(DefaultAssignmentSeeder.class.getName());

    private final AssessmentCategoryRepository categoryRepository;
    private final AdminQuizRepository adminQuizRepository;
    private final ManualQuizQuestionRepository manualQuizQuestionRepository;

    public DefaultAssignmentSeeder(
            AssessmentCategoryRepository categoryRepository,
            AdminQuizRepository adminQuizRepository,
            ManualQuizQuestionRepository manualQuizQuestionRepository) {
        this.categoryRepository = categoryRepository;
        this.adminQuizRepository = adminQuizRepository;
        this.manualQuizQuestionRepository = manualQuizQuestionRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedQuiz("Technical", "Java Basics Assignment", List.of(
                question("JVM stands for?", "Java Virtual Machine", "Java Variable Method", "Joint Virtual Memory", "Java Vendor Module", "Java Virtual Machine"),
                question("Which keyword creates an object?", "new", "class", "this", "void", "new"),
                question("Which is not a Java primitive?", "String", "int", "boolean", "char", "String"),
                question("Which collection stores key-value pairs?", "Map", "List", "Set", "Queue", "Map"),
                question("Which method is Java entry point?", "main", "start", "run", "init", "main")));

            seedQuiz("Aptitude", "Quant Aptitude Assignment", List.of(
                question("25% of 200 is?", "50", "25", "40", "60", "50"),
                question("Average of 4, 8, 12 is?", "8", "6", "10", "12", "8"),
                question("If x + 5 = 12, x = ?", "7", "5", "6", "8", "7"),
                question("15 * 3 equals?", "45", "30", "35", "40", "45"),
                question("Simplify: 81 / 9", "9", "8", "7", "6", "9")));

            seedQuiz("Logical", "Reasoning Assignment", List.of(
                question("Find next: 2, 4, 8, 16, ?", "32", "24", "30", "20", "32"),
                question("Odd one out: Circle, Triangle, Square, Cube", "Cube", "Circle", "Triangle", "Square", "Cube"),
                question("If all A are B, and all B are C, then all A are C?", "True", "False", "Cannot say", "None", "True"),
                question("Mirror opposite of EAST is?", "WEST", "NORTH", "SOUTH", "EAST", "WEST"),
                question("Complete: AB, BC, CD, ?", "DE", "EF", "CE", "DD", "DE")));

            seedQuiz("Personality", "Workstyle Assignment", List.of(
                question("Best trait for teamwork?", "Communication", "Isolation", "Avoidance", "Silence", "Communication"),
                question("You should handle conflict by?", "Listening first", "Ignoring", "Arguing", "Escaping", "Listening first"),
                question("A growth mindset means?", "Learning from feedback", "Never changing", "Avoiding challenges", "Only winning", "Learning from feedback"),
                question("Strong time management includes?", "Prioritizing tasks", "Delaying all", "Random work", "No planning", "Prioritizing tasks"),
                question("Professional behavior includes?", "Reliability", "Excuses", "Blame", "Neglect", "Reliability")));

            seedQuiz("Career Interest", "Career Discovery Assignment", List.of(
                question("You enjoy solving coding problems. Best path?", "Software Development", "Accounting", "Mechanical Repair", "Fashion Design", "Software Development"),
                question("You like analyzing data trends. Best path?", "Data Analytics", "Content Writing", "Animation", "Reception", "Data Analytics"),
                question("You enjoy helping people with hiring. Best path?", "Human Resources", "Network Security", "Civil Engineering", "Architecture", "Human Resources"),
                question("You like designing interfaces. Best path?", "UI/UX Design", "Tax Consulting", "Pharmacology", "Auditing", "UI/UX Design"),
                question("You enjoy securing systems. Best path?", "Cybersecurity", "Graphic Printing", "Travel Desk", "Inventory Clerk", "Cybersecurity")));
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Skipping default assignment seeding because database is not reachable yet.", ex);
        }
    }

    private void seedQuiz(String category, String quizTitle, List<ManualQuizQuestion> defaults) {
        ensureCategoryExists(category);
        ensureQuizExists(category, quizTitle);

        List<ManualQuizQuestion> existing = manualQuizQuestionRepository
                .findByCategoryIgnoreCaseAndQuizTitleIgnoreCaseOrderByIdAsc(category, quizTitle);

        if (!existing.isEmpty()) {
            return;
        }

        for (ManualQuizQuestion question : defaults) {
            question.setCategory(category);
            question.setQuizTitle(quizTitle);
            manualQuizQuestionRepository.save(question);
        }
    }

    private void ensureCategoryExists(String category) {
        if (categoryRepository.existsByNameIgnoreCase(category)) {
            return;
        }

        AssessmentCategory item = new AssessmentCategory();
        item.setName(category);
        item.setNote("Default assignment category");
        categoryRepository.save(item);
    }

    private void ensureQuizExists(String category, String quizTitle) {
        if (adminQuizRepository.existsByCategoryIgnoreCaseAndQuizTitleIgnoreCase(category, quizTitle)) {
            return;
        }

        AdminQuiz quiz = new AdminQuiz();
        quiz.setCategory(category);
        quiz.setQuizTitle(quizTitle);
        adminQuizRepository.save(quiz);
    }

    private ManualQuizQuestion question(String text, String opt1, String opt2, String opt3, String opt4, String answer) {
        ManualQuizQuestion item = new ManualQuizQuestion();
        item.setQuestion(text);
        item.setQuestionType("mcq");
        item.setOption1(opt1);
        item.setOption2(opt2);
        item.setOption3(opt3);
        item.setOption4(opt4);
        item.setAnswer(answer);
        return item;
    }
}
