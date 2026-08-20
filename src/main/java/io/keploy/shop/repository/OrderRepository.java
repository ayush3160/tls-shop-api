package io.keploy.shop.repository;

import io.keploy.shop.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {
    Page<Order> findByUserId(String userId, Pageable pageable);
    Page<Order> findByStatus(String status, Pageable pageable);
    long countByStatus(String status);
    long countByUserId(String userId);
}
