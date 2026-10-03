package com.visitorbridge.listener;

import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookCardEventListener implements CardEventListener {

    private final BookingService bookingService;

    @Override
    public void onCardEvent(NuveqEventDto event) {
        if (event == null) {
            log.warn("Received empty or null card event");
            return;
        }

        String cardNumber = null;
        if (event.getCardNo() != null) {
            cardNumber = String.valueOf(event.getCardNo());
        } else if (event.getCardId() != null) {
            cardNumber = String.valueOf(event.getCardId());
        }

        if (cardNumber == null || cardNumber.isBlank()) {
            log.warn("Card event missing card identifier: eventId={}", event.getId());
            return;
        }

        bookingService.processCardEvent(cardNumber, event.getDirection());
    }
}
