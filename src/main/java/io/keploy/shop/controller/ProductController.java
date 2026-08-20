package io.keploy.shop.controller;

import io.keploy.shop.dto.PageResponse;
import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.Product;
import io.keploy.shop.model.Review;
import io.keploy.shop.repository.ProductRepository;
import io.keploy.shop.repository.ReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository repo;
    private final ReviewRepository reviewRepo;

    public ProductController(ProductRepository repo, ReviewRepository reviewRepo) {
        this.repo = repo;
        this.reviewRepo = reviewRepo;
    }

    @GetMapping
    public PageResponse<Product> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean active) {
        var pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, sort));
        Page<Product> result;
        if (categoryId != null) {
            result = repo.findByCategoryId(categoryId, pageable);
        } else if (minPrice != null && maxPrice != null) {
            result = repo.findByPriceBetween(minPrice, maxPrice, pageable);
        } else if (tag != null) {
            result = repo.findByTagsContaining(tag, pageable);
        } else if (active != null) {
            result = repo.findByActive(active, pageable);
        } else {
            result = repo.findAll(pageable);
        }
        return PageResponse.of(result);
    }

    @GetMapping("/search")
    public PageResponse<Product> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.of(
                repo.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(q, q, pageable));
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Product not found: " + id));
    }

    @GetMapping("/sku/{sku}")
    public Product bySku(@PathVariable String sku) {
        return repo.findBySku(sku)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Product not found: " + sku));
    }

    @GetMapping("/{id}/reviews")
    public PageResponse<Review> reviews(@PathVariable String id,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(reviewRepo.findByProductId(id, pageable));
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Product p) {
        if (p.getSku() == null || p.getSku().isBlank()) {
            throw new ApiExceptions.BadRequestException("sku is required");
        }
        if (repo.existsBySku(p.getSku())) {
            throw new ApiExceptions.ConflictException("sku already exists: " + p.getSku());
        }
        p.setId(null);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(p));
    }

    @PutMapping("/{id}")
    public Product replace(@PathVariable String id, @RequestBody Product body) {
        Product existing = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Product not found: " + id));
        body.setId(existing.getId());
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(Instant.now());
        return repo.save(body);
    }

    @PatchMapping("/{id}")
    public Product patch(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        Product p = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Product not found: " + id));
        if (updates.containsKey("name")) p.setName((String) updates.get("name"));
        if (updates.containsKey("description")) p.setDescription((String) updates.get("description"));
        if (updates.containsKey("price")) p.setPrice(new BigDecimal(updates.get("price").toString()));
        if (updates.containsKey("active")) p.setActive((Boolean) updates.get("active"));
        if (updates.containsKey("categoryId")) p.setCategoryId((String) updates.get("categoryId"));
        p.setUpdatedAt(Instant.now());
        return repo.save(p);
    }

    @PatchMapping("/{id}/stock")
    public Product adjustStock(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Product p = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Product not found: " + id));
        if (body.containsKey("set")) {
            p.setStock(Integer.parseInt(body.get("set").toString()));
        } else if (body.containsKey("delta")) {
            int newStock = p.getStock() + Integer.parseInt(body.get("delta").toString());
            if (newStock < 0) throw new ApiExceptions.BadRequestException("stock cannot go negative");
            p.setStock(newStock);
        } else {
            throw new ApiExceptions.BadRequestException("provide 'set' or 'delta'");
        }
        p.setUpdatedAt(Instant.now());
        return repo.save(p);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) {
            throw new ApiExceptions.NotFoundException("Product not found: " + id);
        }
        reviewRepo.deleteByProductId(id);
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
