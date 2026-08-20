package io.keploy.shop.repository;

import io.keploy.shop.model.Category;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends MongoRepository<Category, String> {
    Optional<Category> findBySlug(String slug);
    boolean existsBySlug(String slug);
    List<Category> findByParentId(String parentId);
    List<Category> findByActive(boolean active);
}
