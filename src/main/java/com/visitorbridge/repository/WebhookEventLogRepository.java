package com.visitorbridge.repository;

import com.visitorbridge.model.WebhookEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookEventLogRepository extends JpaRepository<WebhookEventLog, UUID> {

    List<WebhookEventLog> findByCardNumber(String cardNumber);

    List<WebhookEventLog> findTop20ByOrderByReceivedAtDesc();
}
