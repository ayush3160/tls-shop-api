package io.keploy.shop.service;

import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.Order;
import io.keploy.shop.model.Product;
import io.keploy.shop.repository.OrderRepository;
import io.keploy.shop.repository.ProductRepository;
import io.keploy.shop.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Service
public class OrderService {

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal FLAT_SHIPPING = new BigDecimal("5.00");

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;
    private final UserRepository userRepo;

    public OrderService(OrderRepository orderRepo, ProductRepository productRepo, UserRepository userRepo) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
        this.userRepo = userRepo;
    }

    /** Validates the cart, prices each line from the catalogue, decrements stock, and persists. */
    public Order place(Order order) {
        if (order.getUserId() == null || !userRepo.existsById(order.getUserId())) {
            throw new ApiExceptions.BadRequestException("valid userId is required");
        }
        if (order.getItems() == null || order.getItems().isEmpty()) {
            throw new ApiExceptions.BadRequestException("order must contain at least one item");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Order.LineItem item : order.getItems()) {
            Product product = productRepo.findById(item.getProductId())
                    .orElseThrow(() -> new ApiExceptions.BadRequestException(
                            "product not found: " + item.getProductId()));
            if (item.getQuantity() <= 0) {
                throw new ApiExceptions.BadRequestException("quantity must be positive for " + product.getSku());
            }
            if (product.getStock() < item.getQuantity()) {
                throw new ApiExceptions.ConflictException("insufficient stock for " + product.getSku());
            }
            // Price authoritatively from the catalogue, never from the client.
            item.setSku(product.getSku());
            item.setName(product.getName());
            item.setUnitPrice(product.getPrice());
            subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

            product.setStock(product.getStock() - item.getQuantity());
            product.setUpdatedAt(Instant.now());
            productRepo.save(product);
        }

        BigDecimal tax = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal shipping = subtotal.signum() > 0 ? FLAT_SHIPPING : BigDecimal.ZERO;
        order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        order.setTax(tax);
        order.setShipping(shipping);
        order.setTotal(subtotal.add(tax).add(shipping).setScale(2, RoundingMode.HALF_UP));
        order.setId(null);
        if (order.getStatus() == null) order.setStatus("PENDING");
        order.setCreatedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }

    private static final java.util.Set<String> VALID_STATUSES = java.util.Set.of(
            "PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED");

    public Order updateStatus(String id, String status) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Order not found: " + id));
        if (status == null || !VALID_STATUSES.contains(status.toUpperCase())) {
            throw new ApiExceptions.BadRequestException("invalid status: " + status);
        }
        order.setStatus(status.toUpperCase());
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }

    /** Cancelling restocks the reserved units. */
    public Order cancel(String id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Order not found: " + id));
        if ("CANCELLED".equals(order.getStatus())) {
            return order;
        }
        for (Order.LineItem item : order.getItems()) {
            productRepo.findById(item.getProductId()).ifPresent(p -> {
                p.setStock(p.getStock() + item.getQuantity());
                productRepo.save(p);
            });
        }
        order.setStatus("CANCELLED");
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }
}
