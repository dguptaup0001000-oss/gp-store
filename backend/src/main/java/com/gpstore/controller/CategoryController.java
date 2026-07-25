package com.gpstore.controller;

import com.gpstore.entity.Category;
import com.gpstore.service.CategoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // Admin only (enforced in SecurityConfig).
    @PostMapping
    public Category createCategory(@RequestBody Category category) {
        return categoryService.saveCategory(category);
    }

    @GetMapping
    public List<Category> getAllCategories() {
        return categoryService.getAllCategories();
    }

    @GetMapping("/{id}")
    public Category getById(@PathVariable Long id) {
        return categoryService.getById(id);
    }

    // Admin only (enforced in SecurityConfig).
    @PutMapping("/{id}")
    public Category update(@PathVariable Long id, @RequestBody Category category) {
        return categoryService.update(id, category);
    }

    // Admin only (enforced in SecurityConfig) - soft delete, safe with existing products.
    @DeleteMapping("/{id}")
    public String deactivate(@PathVariable Long id) {
        categoryService.deactivate(id);
        return "Category deactivated";
    }

    // Admin only - permanent delete, blocked if products still reference this category.
    @DeleteMapping("/{id}/permanent")
    public String hardDelete(@PathVariable Long id) {
        categoryService.hardDelete(id);
        return "Category permanently deleted";
    }
}
