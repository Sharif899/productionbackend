package com.sharif899.prodbackend.repository;

import com.sharif899.prodbackend.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByReference(String reference);

    // The entity holds an Order association called "order", not an
    // orderId field, so the derived query traverses it: order.id.
    List<Payment> findByOrder_Id(UUID orderId);

    boolean existsByOrder_Id(UUID orderId);
}
