package com.kasa.customer;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.customer.CustomerService.CustomerPage;
import com.kasa.customer.CustomerService.CustomerView;
import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 200;

    public record CustomerRequest(@NotBlank @Email @Size(max = 254) String email, @Size(max = 200) String name) {
    }

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView create(Authentication authentication, @Valid @RequestBody CustomerRequest request) {
        return customers.create(CurrentTenant.of(authentication), request.email(), blankToNull(request.name()));
    }

    @GetMapping
    public CustomerPage list(Authentication authentication,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) UUID before,
                             @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is too long");
        }
        return customers.list(CurrentTenant.of(authentication), blankToNull(q), before, limit);
    }

    @GetMapping("/{id}")
    public CustomerView get(Authentication authentication, @PathVariable UUID id) {
        return customers.get(CurrentTenant.of(authentication), id);
    }

    @PutMapping("/{id}")
    public CustomerView update(Authentication authentication, @PathVariable UUID id,
                               @Valid @RequestBody CustomerRequest request) {
        return customers.update(CurrentTenant.of(authentication), id, request.email(), blankToNull(request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymize(Authentication authentication, @PathVariable UUID id) {
        customers.anonymize(CurrentTenant.of(authentication), id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
