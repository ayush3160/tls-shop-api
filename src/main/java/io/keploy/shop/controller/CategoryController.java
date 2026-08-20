package io.keploy.shop.controller;

import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.Category;
import io.keploy.shop.repository.CategoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository repo;

    public CategoryController(CategoryRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Category> list(@RequestParam(required = false) Boolean active,
                               @RequestParam(required = false) String parentId) {
        if (active != null) return repo.findByActive(active);
        if (parentId != null) return repo.findByParentId(parentId);
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public Category get(@PathVariable String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found: " + id));
    }

    @GetMapping("/slug/{slug}")
    public Category bySlug(@PathVariable String slug) {
        return repo.findBySlug(slug)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found: " + slug));
    }

    @PostMapping
    public ResponseEntity<Category> create(@RequestBody Category c) {
        if (c.getSlug() == null || c.getSlug().isBlank()) {
            throw new ApiExceptions.BadRequestException("slug is required");
        }
        if (repo.existsBySlug(c.getSlug())) {
            throw new ApiExceptions.ConflictException("slug already exists: " + c.getSlug());
        }
        c.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(c));
    }

    @PutMapping("/{id}")
    public Category replace(@PathVariable String id, @RequestBody Category body) {
        Category existing = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found: " + id));
        body.setId(existing.getId());
        body.setCreatedAt(existing.getCreatedAt());
        return repo.save(body);
    }

    @PatchMapping("/{id}")
    public Category patch(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        Category c = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Category not found: " + id));
        if (updates.containsKey("name")) c.setName((String) updates.get("name"));
        if (updates.containsKey("description")) c.setDescription((String) updates.get("description"));
        if (updates.containsKey("active")) c.setActive((Boolean) updates.get("active"));
        if (updates.containsKey("parentId")) c.setParentId((String) updates.get("parentId"));
        return repo.save(c);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) {
            throw new ApiExceptions.NotFoundException("Category not found: " + id);
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
