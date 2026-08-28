package com.gpstore.service;

import com.gpstore.entity.Category;
import com.gpstore.exception.BadRequestException;
import com.gpstore.repository.CategoryRepository;
import com.gpstore.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryServiceUpdateTest {

    private CategoryRepository categoryRepository;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        service = new CategoryService(categoryRepository, productRepository,
                mock(com.gpstore.upload.CatalogImageCleanup.class));
    }

    @Test
    void omittedImageUrlDoesNotWipeTheExistingPhoto() {
        Category existing = new Category();
        existing.setId(7L);
        existing.setName("Atta");
        existing.setImageUrl("https://res.cloudinary.com/demo/image/upload/v1/gp/atta.jpg");
        existing.setActive(true);
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(existing)).thenReturn(existing);

        Category updated = new Category();
        updated.setName("Atta flour");
        updated.setDescription("Staples");
        updated.setImageUrl(null);
        updated.setActive(true);

        Category saved = service.update(7L, updated);
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/gp/atta.jpg",
                saved.getImageUrl(),
                "Flutter omits imageUrl on rename; that must not clear the photo");
    }

    @Test
    void emptyImageUrlClearsThePhoto() {
        Category existing = new Category();
        existing.setId(7L);
        existing.setName("Atta");
        existing.setImageUrl("https://res.cloudinary.com/demo/image/upload/v1/gp/atta.jpg");
        when(categoryRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(existing)).thenReturn(existing);

        Category updated = new Category();
        updated.setName("Atta");
        updated.setImageUrl("");
        updated.setActive(true);

        assertNull(service.update(7L, updated).getImageUrl());
    }

    @Test
    void evilImageUrlIsRefusedOnCreate() {
        Category category = new Category();
        category.setName("Evil");
        category.setImageUrl("https://res.cloudinary.com.evil.example/x.jpg");
        assertThrows(BadRequestException.class, () -> service.saveCategory(category));
        verify(categoryRepository, never()).save(any());
    }
}
