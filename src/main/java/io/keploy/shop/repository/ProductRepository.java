package io.keploy.shop.repository;

import io.keploy.shop.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.math.BigDecimal;
import java.util.Optional;

public interface ProductRepository extends MongoRepository<Product, String> {
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);
    Page<Product> findByCategoryId(String categoryId, Pageable pageable);
    Page<Product> findByActive(boolean active, Pageable pageable);
    Page<Product> findByPriceBetween(BigDecimal min, BigDecimal max, Pageable pageable);
    Page<Product> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String description, Pageable pageable);
    Page<Product> findByTagsContaining(String tag, Pageable pageable);
    long countByCategoryId(String categoryId);
}
