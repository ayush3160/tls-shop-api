package io.keploy.shop.controller;

import io.keploy.shop.dto.PageResponse;
import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.Order;
import io.keploy.shop.repository.OrderRepository;
import io.keploy.shop.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository repo;
    private final OrderService service;

    public OrderController(OrderRepository repo, OrderService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping
    public PageResponse<Order> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(status == null
                ? repo.findAll(pageable)
                : repo.findByStatus(status, pageable));
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("Order not found: " + id));
    }

    @GetMapping("/user/{userId}")
    public PageResponse<Order> byUser(@PathVariable String userId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(repo.findByUserId(userId, pageable));
    }

    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam(required = false) String status) {
        return Map.of("count", status == null ? repo.count() : repo.countByStatus(status));
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody Order order) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.place(order));
    }

    @PatchMapping("/{id}/status")
    public Order updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        return service.updateStatus(id, body.get("status"));
    }

    @PostMapping("/{id}/cancel")
    public Order cancel(@PathVariable String id) {
        return service.cancel(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) {
            throw new ApiExceptions.NotFoundException("Order not found: " + id);
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
