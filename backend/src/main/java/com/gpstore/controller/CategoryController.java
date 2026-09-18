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

    /**
     * The platform's taxonomy - every department GP-STORE knows about.
     *
     * <p>Stays whole on purpose. This is what the Add Product screen offers a
     * merchant to choose from, and what the customer app browses. A merchant
     * picking "Mobile Phones" for the first time needs to see a category their
     * shop does not trade in yet.
     */
    @GetMapping
    public List<Category> getAllCategories() {
        return categoryService.getAllCategories();
    }

    /**
     * The departments the calling shop actually trades in.
     *
     * <p>SEPARATE FROM THE TAXONOMY ABOVE because they are different questions,
     * and answering the second with the first is what showed a new phone shop
     * a management list of "Atta, Rice & Dal ... for everyday kirana needs".
     * See CategoryService.getCategoriesOnMyShelf.
     *
     * <p>Not a permission boundary and not authorization - a merchant's own
     * shelf decides what is on it. Ownership is enforced where it belongs, on
     * the listing rows.
     */
    @GetMapping("/mine")
    public List<Category> getMyCategories() {
        return categoryService.getCategoriesOnMyShelf();
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
