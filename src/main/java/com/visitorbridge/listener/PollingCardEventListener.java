package com.visitorbridge.listener;

import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "nuveq.event-listener", name = "mode", havingValue = "polling")
@RequiredArgsConstructor
public class PollingCardEventListener {

    private final NuveqVisitorClient nuveqVisitorClient;
    private final CardEventListener cardEventListener;
    private final Set<Long> processedEventIds = new HashSet<>();

    @Scheduled(fixedDelayString = "${nuveq.event-listener.polling-interval-ms:10000}")
    public void pollEvents() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        log.debug("Polling Nuveq card events for date: {}", today);

        List<NuveqEventDto> events = nuveqVisitorClient.fetchEvents(today);
        for (NuveqEventDto event : events) {
            if (event.getId() != null && !processedEventIds.contains(event.getId())) {
                processedEventIds.add(event.getId());
                cardEventListener.onCardEvent(event);
            }
        }
    }
}
