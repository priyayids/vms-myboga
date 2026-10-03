package com.visitorbridge.listener;

import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.config.NuveqProperties;
import com.visitorbridge.config.VmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pulls card events from Nuveq when the deployment cannot receive webhooks (localhost,
 * demo device, or a site behind NAT). Complements the push-based webhook path; only one
 * is active at a time via {@code nuveq.event-listener.mode}.
 *
 * <p>Polls "today" in the site timezone, not the host timezone. Those differ whenever the
 * container runs UTC, which would shift the window and miss the late-evening check-outs
 * that matter most.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nuveq.event-listener", name = "mode", havingValue = "polling")
@RequiredArgsConstructor
public class PollingCardEventListener {

    private final NuveqVisitorClient nuveqVisitorClient;
    private final CardEventListener cardEventListener;
    private final NuveqProperties nuveqProperties;
    private final VmsProperties vmsProperties;

    /**
     * Insertion-ordered so the oldest id can be evicted once the cap is reached. A plain
     * HashSet would grow without bound for the lifetime of the process.
     */
    private final Set<Long> processedEventIds = new LinkedHashSet<>();

    @Scheduled(fixedDelayString = "${nuveq.event-listener.polling-interval-ms:10000}")
    public void pollEvents() {
        String today = LocalDate.now(ZoneId.of(vmsProperties.getBooking().getTimezone()))
                .format(DateTimeFormatter.ISO_DATE);
        log.debug("Polling Nuveq card events for date: {}", today);

        List<NuveqEventDto> events;
        try {
            events = nuveqVisitorClient.fetchEvents(today);
        } catch (RuntimeException ex) {
            // Never let a transient Nuveq failure kill the scheduled task.
            log.warn("NUVEQ_POLL | date={} | result=FAILED | error={}", today, ex.getMessage());
            return;
        }

        for (NuveqEventDto event : events) {
            Long id = event.getId();
            if (id == null || processedEventIds.contains(id)) {
                continue;
            }
            processedEventIds.add(id);
            evictIfNeeded();
            cardEventListener.onCardEvent(event);
        }
    }

    /**
     * Keeps the id cache bounded. Evicting the oldest entries only risks re-processing an
     * event if the same id appears again beyond the cap, which is preferable to unbounded
     * heap growth in a scheduler that runs forever.
     */
    private void evictIfNeeded() {
        int capacity = nuveqProperties.getEventListener().getProcessedEventsCapacity();
        if (capacity <= 0) {
            return;
        }
        while (processedEventIds.size() > capacity) {
            var it = processedEventIds.iterator();
            it.next();
            it.remove();
        }
    }

    /** Test seam. */
    int processedEventCount() {
        return processedEventIds.size();
    }
}