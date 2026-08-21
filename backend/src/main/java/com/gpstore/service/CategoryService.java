package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.exception.ConflictException;
import com.gpstore.exception.ResourceNotFoundException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public Category saveCategory(Category category) {
        if (category.getActive() == null) {
            category.setActive(true);
        }
        return categoryRepository.save(category);
    }

    @Cacheable("categories")
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    public Category getById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public Category update(Long id, Category updated) {
        Category existing = getById(id);

        existing.setName(updated.getName());
        existing.setDescription(updated.getDescription());
        existing.setImageUrl(updated.getImageUrl());
        existing.setGstRate(updated.getGstRate());
        existing.setActive(updated.getActive());

        return categoryRepository.save(existing);
    }

    /**
     * Soft-delete only (active=false) - a hard delete would either orphan every
     * product still pointing at this category, or fail on the FK constraint.
     * Products keep their category reference; the storefront just needs to
     * stop showing a deactivated category.
     */
    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public void deactivate(Long id) {
        Category category = getById(id);
        category.setActive(false);
        categoryRepository.save(category);
    }

    // bestsellerTiles too: the collage carries each category's name and is
    // filtered on active, so renaming or deactivating one must not leave a
    // stale tile on every customer's home screen.
    @CacheEvict(value = {"categories", "bestsellerTiles"}, allEntries = true)
    public void hardDelete(Long id) {
        if (!productRepository.findByCategoryId(id).isEmpty()) {
            throw new ConflictException(
                    "Cannot delete a category that still has products - deactivate it instead, or move the products first");
        }
        categoryRepository.deleteById(id);
    }
}
