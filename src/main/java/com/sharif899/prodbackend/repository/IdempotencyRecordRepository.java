package com.sharif899.prodbackend.repository;

import com.sharif899.prodbackend.domain.IdempotencyKeyId;
import com.sharif899.prodbackend.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyKeyId> {
}
