package io.keploy.shop.repository;

import io.keploy.shop.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ReviewRepository extends MongoRepository<Review, String> {
    Page<Review> findByProductId(String productId, Pageable pageable);
    List<Review> findByProductId(String productId);
    Page<Review> findByUserId(String userId, Pageable pageable);
    long countByProductId(String productId);
    void deleteByProductId(String productId);
}
