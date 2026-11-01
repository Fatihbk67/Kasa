package com.kasa.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.kasa.common.BusinessCalendar;

/** SQL for payments. Every method except {@link #findStaleOperations} expects a transaction bound to a tenant. */
@Component
class PaymentStore {

    record StaleOperation(UUID id, UUID tenantId, UUID paymentId) {
    }

    private final JdbcClient jdbc;

    PaymentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes requests with the same key until commit, before any row exists that could be locked. */
    void lockIdempotencyKey(UUID tenantId, String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", tenantId + ":" + key)
                .query((rs, rowNum) -> 1)
                .single();
    }

    Optional<PaymentOperation> findOperationByKey(String key) {
        return jdbc.sql("SELECT * FROM payment_operations WHERE idempotency_key = :key")
                .param("key", key)
                .query(PaymentStore::mapOperation)
                .optional();
    }

    PaymentOperation getOperation(UUID operationId) {
        return jdbc.sql("SELECT * FROM payment_operations WHERE id = :id")
                .param("id", operationId)
                .query(PaymentStore::mapOperation)
                .single();
    }

    List<PaymentOperation> findOperations(UUID paymentId) {
        return jdbc.sql("SELECT * FROM payment_operations WHERE payment_id = :id ORDER BY created_at, id")
                .param("id", paymentId)
                .query(PaymentStore::mapOperation)
                .list();
    }

    boolean hasPendingOperation(UUID paymentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment_operations WHERE payment_id = :id AND status = 'PENDING')")
                .param("id", paymentId)
                .query(Boolean.class)
                .single();
    }

    void insertPayment(UUID id, UUID tenantId, long amount, String currency, String description, UUID customerId) {
        jdbc.sql("""
                        INSERT INTO payments (id, tenant_id, amount, currency, status, description, customer_id)
                        VALUES (:id, :tenantId, :amount, :currency, 'PENDING', :description, :customerId)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("amount", amount)
                .param("currency", currency)
                .param("description", description)
                .param("customerId", customerId)
                .update();
    }

    /** Row level security hides other tenants' customers, so someone else's id reads as unknown. */
    boolean activeCustomerExists(UUID customerId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM customers WHERE id = :id AND anonymized_at IS NULL)")
                .param("id", customerId)
                .query(Boolean.class)
                .single();
    }

    PaymentOperation insertOperation(UUID tenantId, UUID paymentId, OperationType type, long amount, String key,
                                     String requestHash) {
        return jdbc.sql("""
                        INSERT INTO payment_operations
                            (id, tenant_id, payment_id, type, amount, status, idempotency_key, request_hash)
                        VALUES (:id, :tenantId, :paymentId, :type, :amount, 'PENDING', :key, :hash)
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("paymentId", paymentId)
                .param("type", type.name())
                .param("amount", amount)
                .param("key", key)
                .param("hash", requestHash)
                .query(PaymentStore::mapOperation)
                .single();
    }

    Optional<Payment> find(UUID paymentId) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id")
                .param("id", paymentId)
                .query(PaymentStore::mapPayment)
                .optional();
    }

    Optional<Payment> lock(UUID paymentId) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id FOR UPDATE")
                .param("id", paymentId)
                .query(PaymentStore::mapPayment)
                .optional();
    }

    List<Payment> list(PaymentFilter filter, UUID before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM payments WHERE true");
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.query() != null) {
            addTextSearch(filter.query(), sql, params);
        }
        if (filter.from() != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", BusinessCalendar.startOf(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND created_at < :to");
            params.put("to", BusinessCalendar.startOf(filter.to().plusDays(1)));
        }
        if (filter.minAmount() != null) {
            sql.append(" AND amount >= :minAmount");
            params.put("minAmount", filter.minAmount());
        }
        if (filter.maxAmount() != null) {
            sql.append(" AND amount <= :maxAmount");
            params.put("maxAmount", filter.maxAmount());
        }
        if (filter.customerId() != null) {
            sql.append(" AND customer_id = :customerId");
            params.put("customerId", filter.customerId());
        }
        if (before != null) {
            sql.append(" AND (created_at, id) < (SELECT created_at, id FROM payments WHERE id = :before)");
            params.put("before", before);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        return jdbc.sql(sql.toString()).params(params).query(PaymentStore::mapPayment).list();
    }

    private static void addTextSearch(String query, StringBuilder sql, Map<String, Object> params) {
        try {
            params.put("id", UUID.fromString(query));
            sql.append(" AND id = :id");
        } catch (IllegalArgumentException notAnId) {
            // Escaped so a typed % or _ means itself rather than a wildcard.
            String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.put("description", "%" + escaped + "%");
            sql.append(" AND description ILIKE :description");
        }
    }

    void update(UUID paymentId, PaymentStatus status, long captured, long refunded, long fee, String bankReference,
                String failureCode) {
        jdbc.sql("""
                        UPDATE payments
                        SET status = :status, captured_amount = :captured, refunded_amount = :refunded,
                            fee_amount = :fee, bank_reference = :bankReference, failure_code = :failureCode,
                            updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", paymentId)
                .param("status", status.name())
                .param("captured", captured)
                .param("refunded", refunded)
                .param("fee", fee)
                .param("bankReference", bankReference)
                .param("failureCode", failureCode)
                .update();
    }

    void completeOperation(UUID operationId, OperationStatus status, String bankReference, String failureCode) {
        jdbc.sql("""
                        UPDATE payment_operations
                        SET status = :status, bank_reference = :bankReference, failure_code = :failureCode,
                            updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", operationId)
                .param("status", status.name())
                .param("bankReference", bankReference)
                .param("failureCode", failureCode)
                .update();
    }

    List<StaleOperation> findStaleOperations(Duration olderThan, int limit) {
        return jdbc.sql("SELECT id, tenant_id, payment_id FROM find_stale_payment_operations(:seconds, :limit)")
                .param("seconds", olderThan.toSeconds())
                .param("limit", limit)
                .query((rs, rowNum) -> new StaleOperation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class)))
                .list();
    }

    private static Payment mapPayment(ResultSet rs, int rowNum) throws SQLException {
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getLong("amount"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getLong("captured_amount"),
                rs.getLong("refunded_amount"),
                rs.getLong("fee_amount"),
                rs.getLong("disputed_amount"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("description"),
                rs.getString("bank_reference"),
                rs.getString("failure_code"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static PaymentOperation mapOperation(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                OperationType.valueOf(rs.getString("type")),
                rs.getLong("amount"),
                OperationStatus.valueOf(rs.getString("status")),
                rs.getString("request_hash"),
                rs.getString("failure_code"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
