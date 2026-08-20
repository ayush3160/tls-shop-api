package io.keploy.shop.controller;

import io.keploy.shop.dto.PageResponse;
import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.Product;
import io.keploy.shop.model.Review;
import io.keploy.shop.repository.ProductRepository;
import io.keploy.shop.repository.ReviewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewRepository repo;
    private final ProductRepository productRepo;

    public ReviewController(ReviewRepository repo, ProductRepository productRepo) {
        this.repo = repo;
        this.productRepo = productRepo;
    }

    @GetMapping
    public PageResponse<Review> list(
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        if (productId != null) return PageResponse.of(repo.findByProductId(productId, pageable));
        if (userId != null) return PageResponse.of(repo.findByUserId(userId, pageable));
        return PageResponse.of(repo.findAll(pageable));
    }

    @GetMapping("/{id}")
    public Review get(@PathVariable String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Review not found: " + id));
    }

    @PostMapping
    public ResponseEntity<Review> create(@RequestBody Review r) {
        if (r.getProductId() == null || !productRepo.existsById(r.getProductId())) {
            throw new ApiExceptions.BadRequestException("valid productId is required");
        }
        if (r.getRating() < 1 || r.getRating() > 5) {
            throw new ApiExceptions.BadRequestException("rating must be between 1 and 5");
        }
        r.setId(null);
        r.setCreatedAt(Instant.now());
        Review saved = repo.save(r);
        recomputeRating(r.getProductId());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PostMapping("/{id}/helpful")
    public Review markHelpful(@PathVariable String id) {
        Review r = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Review not found: " + id));
        r.setHelpfulVotes(r.getHelpfulVotes() + 1);
        return repo.save(r);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Review r = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Review not found: " + id));
        repo.deleteById(id);
        recomputeRating(r.getProductId());
        return ResponseEntity.noContent().build();
    }

    /** Recomputes the denormalised rating/reviewCount fields on the parent product. */
    private void recomputeRating(String productId) {
        productRepo.findById(productId).ifPresent(p -> {
            List<Review> all = repo.findByProductId(productId);
            if (all.isEmpty()) {
                p.setRating(0.0);
                p.setReviewCount(0);
            } else {
                double avg = all.stream().mapToInt(Review::getRating).average().orElse(0.0);
                p.setRating(Math.round(avg * 100.0) / 100.0);
                p.setReviewCount(all.size());
            }
            productRepo.save(p);
        });
    }
}
