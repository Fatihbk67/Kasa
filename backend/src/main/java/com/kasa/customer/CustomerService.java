package com.kasa.customer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.audit.AuditLog;
import com.kasa.auth.AuthService;
import com.kasa.tenant.TenantScope;

/**
 * Personal data stays in the customers table. The audit log can never be edited, so it records only ids here:
 * anything written there about a person could not be erased when they ask for it.
 */
@Service
public class CustomerService {

    /** One entry per currency, because amounts in different currencies cannot be added up. */
    public record CurrencyTotal(String currency, long spent, long paymentCount) {
    }

    public record CustomerView(UUID id, String email, String name, boolean anonymized, OffsetDateTime createdAt,
                               List<CurrencyTotal> totals) {
    }

    public record CustomerPage(List<CustomerView> items, UUID nextCursor) {
    }

    private record Row(UUID id, String email, String name, boolean anonymized, OffsetDateTime createdAt) {
        CustomerView view(List<CurrencyTotal> totals) {
            return new CustomerView(id, email, name, anonymized, createdAt, totals);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final AuditLog auditLog;

    public CustomerService(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.auditLog = auditLog;
    }

    public CustomerView create(UUID tenantId, String email, String name) {
        String normalized = AuthService.normalize(email);
        UUID id = UUID.randomUUID();
        try {
            return tx.execute(status -> {
                tenantScope.apply(tenantId);
                jdbc.sql("INSERT INTO customers (id, tenant_id, email, name) VALUES (:id, :tenantId, :email, :name)")
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("email", normalized)
                        .param("name", name)
                        .update();
                auditLog.record(tenantId, "CUSTOMER_CREATED", "CUSTOMER", id, Map.of());
                return load(id);
            });
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a customer with this email already exists");
        }
    }

    /**
     * For a public payment form. An anonymous visitor must not be able to rename an existing customer, so a known
     * email keeps its stored name. The update touches only the timestamp, but it makes RETURNING work on conflict.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID findOrCreate(UUID tenantId, String email, String name) {
        return jdbc.sql("""
                        INSERT INTO customers (id, tenant_id, email, name)
                        VALUES (:id, :tenantId, :email, :name)
                        ON CONFLICT (tenant_id, email) WHERE email IS NOT NULL
                        DO UPDATE SET updated_at = now()
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("email", AuthService.normalize(email))
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    public CustomerView update(UUID tenantId, UUID id, String email, String name) {
        String normalized = AuthService.normalize(email);
        try {
            return tx.execute(status -> {
                tenantScope.apply(tenantId);
                int updated = jdbc.sql("""
                                UPDATE customers SET email = :email, name = :name, updated_at = now()
                                WHERE id = :id AND anonymized_at IS NULL
                                """)
                        .param("email", normalized)
                        .param("name", name)
                        .param("id", id)
                        .update();
                if (updated == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found");
                }
                auditLog.record(tenantId, "CUSTOMER_UPDATED", "CUSTOMER", id, Map.of());
                return load(id);
            });
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a customer with this email already exists");
        }
    }

    /**
     * Honours a person's request to be forgotten. Financial records must be kept, so payments stay and lose only
     * their link to a name. Waits for open disputes, whose evidence may still need to identify the customer.
     */
    public void anonymize(UUID tenantId, UUID id) {
        tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantId);
            boolean openDispute = jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM disputes d
                                           JOIN payments p ON p.id = d.payment_id
                                           WHERE p.customer_id = :id AND d.status IN ('NEEDS_RESPONSE', 'UNDER_REVIEW'))
                            """)
                    .param("id", id)
                    .query(Boolean.class)
                    .single();
            if (openDispute) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "this customer has an open dispute");
            }
            int updated = jdbc.sql("""
                            UPDATE customers SET email = NULL, name = NULL, anonymized_at = now(), updated_at = now()
                            WHERE id = :id AND anonymized_at IS NULL
                            """)
                    .param("id", id)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found");
            }
            auditLog.record(tenantId, "CUSTOMER_ANONYMIZED", "CUSTOMER", id, Map.of());
        });
    }

    public CustomerView get(UUID tenantId, UUID id) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            return load(id);
        });
    }

    public CustomerPage list(UUID tenantId, String query, UUID before, int limit) {
        return tx.execute(status -> {
            tenantScope.apply(tenantId);
            StringBuilder sql = new StringBuilder("SELECT * FROM customers WHERE true");
            Map<String, Object> params = new HashMap<>();
            params.put("limit", limit + 1);
            if (query != null) {
                // Escaped so a typed % or _ means itself rather than a wildcard.
                String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                params.put("query", "%" + escaped + "%");
                sql.append(" AND (email ILIKE :query OR name ILIKE :query)");
            }
            if (before != null) {
                sql.append(" AND (created_at, id) < (SELECT created_at, id FROM customers WHERE id = :before)");
                params.put("before", before);
            }
            sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
            List<Row> rows = jdbc.sql(sql.toString()).params(params).query(CustomerService::mapRow).list();
            boolean hasMore = rows.size() > limit;
            List<Row> page = hasMore ? rows.subList(0, limit) : rows;
            Map<UUID, List<CurrencyTotal>> totals = totalsOf(page.stream().map(Row::id).toList());
            return new CustomerPage(page.stream().map(row -> row.view(totals.getOrDefault(row.id(), List.of()))).toList(),
                    hasMore ? page.getLast().id() : null);
        });
    }

    private CustomerView load(UUID id) {
        Row row = jdbc.sql("SELECT * FROM customers WHERE id = :id")
                .param("id", id)
                .query(CustomerService::mapRow)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "customer not found"));
        return row.view(totalsOf(List.of(id)).getOrDefault(id, List.of()));
    }

    /** Spent is what the merchant kept: refunds and money the bank took back through disputes do not count. */
    private Map<UUID, List<CurrencyTotal>> totalsOf(List<UUID> ids) {
        Map<UUID, List<CurrencyTotal>> totals = new HashMap<>();
        if (ids.isEmpty()) {
            return totals;
        }
        jdbc.sql("""
                        SELECT customer_id, currency,
                               SUM(captured_amount - refunded_amount - disputed_amount) AS spent,
                               COUNT(*) AS payment_count
                        FROM payments
                        WHERE customer_id IN (:ids) AND captured_amount > 0
                        GROUP BY customer_id, currency
                        ORDER BY currency
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> {
                    totals.computeIfAbsent(rs.getObject("customer_id", UUID.class), id -> new ArrayList<>())
                            .add(new CurrencyTotal(rs.getString("currency"), rs.getLong("spent"), rs.getLong("payment_count")));
                    return 1;
                })
                .list();
        return totals;
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("name"),
                rs.getObject("anonymized_at", OffsetDateTime.class) != null,
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
