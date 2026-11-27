package com.sharif899.prodbackend.repository;

import com.sharif899.prodbackend.domain.Order;
import com.sharif899.prodbackend.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByClientReference(String clientReference);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);
}
