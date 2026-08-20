package io.keploy.shop.controller;

import io.keploy.shop.dto.PageResponse;
import io.keploy.shop.exception.ApiExceptions;
import io.keploy.shop.model.User;
import io.keploy.shop.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository repo;

    public UserController(UserRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public PageResponse<User> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role) {
        var pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC, sort));
        Page<User> result;
        if (status != null) {
            result = repo.findByStatus(status, pageable);
        } else if (role != null) {
            result = repo.findByRole(role, pageable);
        } else {
            result = repo.findAll(pageable);
        }
        return PageResponse.of(result);
    }

    @GetMapping("/search")
    public PageResponse<User> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.of(
                repo.findByFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(q, q, pageable));
    }

    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam(required = false) String status) {
        long c = status == null ? repo.count() : repo.countByStatus(status);
        return Map.of("count", c);
    }

    @GetMapping("/{id}")
    public User get(@PathVariable String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found: " + id));
    }

    @GetMapping("/by-email")
    public User byEmail(@RequestParam String email) {
        return repo.findByEmail(email)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found: " + email));
    }

    @PostMapping
    public ResponseEntity<User> create(@RequestBody User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new ApiExceptions.BadRequestException("email is required");
        }
        if (repo.existsByEmail(user.getEmail())) {
            throw new ApiExceptions.ConflictException("email already exists: " + user.getEmail());
        }
        user.setId(null);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(user));
    }

    @PutMapping("/{id}")
    public User replace(@PathVariable String id, @RequestBody User body) {
        User existing = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found: " + id));
        body.setId(existing.getId());
        body.setCreatedAt(existing.getCreatedAt());
        body.setUpdatedAt(Instant.now());
        return repo.save(body);
    }

    @PatchMapping("/{id}")
    public User patch(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        User u = repo.findById(id)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("User not found: " + id));
        if (updates.containsKey("fullName")) u.setFullName((String) updates.get("fullName"));
        if (updates.containsKey("phone")) u.setPhone((String) updates.get("phone"));
        if (updates.containsKey("role")) u.setRole((String) updates.get("role"));
        if (updates.containsKey("status")) u.setStatus((String) updates.get("status"));
        u.setUpdatedAt(Instant.now());
        return repo.save(u);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) {
            throw new ApiExceptions.NotFoundException("User not found: " + id);
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
