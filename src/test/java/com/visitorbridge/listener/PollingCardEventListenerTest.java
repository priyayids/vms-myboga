package com.visitorbridge.listener;

import com.visitorbridge.client.NuveqEventDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.config.NuveqProperties;
import com.visitorbridge.config.VmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollingCardEventListenerTest {

    @Mock
    private NuveqVisitorClient nuveqVisitorClient;

    @Mock
    private CardEventListener cardEventListener;

    private NuveqProperties nuveqProperties;
    private VmsProperties vmsProperties;
    private PollingCardEventListener listener;

    @BeforeEach
    void setUp() {
        nuveqProperties = new NuveqProperties();
        nuveqProperties.getEventListener().setProcessedEventsCapacity(3);

        vmsProperties = new VmsProperties();
        vmsProperties.getBooking().setTimezone("Asia/Jakarta");

        listener = new PollingCardEventListener(
                nuveqVisitorClient, cardEventListener, nuveqProperties, vmsProperties);
    }

    private static NuveqEventDto event(long id) {
        NuveqEventDto e = new NuveqEventDto();
        e.setId(id);
        return e;
    }

    @Test
    @DisplayName("forwards each unseen event exactly once and suppresses repeats")
    void forwardsUnseenEventsOnce() {
        when(nuveqVisitorClient.fetchEvents(anyString())).thenReturn(List.of(event(1L), event(2L)));

        listener.pollEvents();
        listener.pollEvents();
        listener.pollEvents();

        // NuveqEventDto has no value equality, so match on the id instead of the instance.
        var captor = org.mockito.ArgumentCaptor.forClass(NuveqEventDto.class);
        verify(cardEventListener, times(2)).onCardEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(NuveqEventDto::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("polls using the site timezone, not the host timezone")
    void pollsInSiteTimezone() {
        when(nuveqVisitorClient.fetchEvents(anyString())).thenReturn(List.of());

        listener.pollEvents();

        // Host zone in CI is UTC; the site is Asia/Jakarta (UTC+7). Either way the date must
        // come from the configured zone rather than LocalDate.now()'s system default.
        String expected = java.time.LocalDate
                .now(java.time.ZoneId.of("Asia/Jakarta"))
                .toString();
        verify(nuveqVisitorClient).fetchEvents(expected);
    }

    @Test
    @DisplayName("bounds the processed-id cache so a long-running poller cannot leak")
    void boundsProcessedEventCache() {
        when(nuveqVisitorClient.fetchEvents(anyString()))
                .thenReturn(List.of(event(1L), event(2L), event(3L), event(4L), event(5L)));

        listener.pollEvents();

        assertThat(listener.processedEventCount()).isEqualTo(3);
        verify(cardEventListener, times(5)).onCardEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a Nuveq outage does not kill the scheduler")
    void survivesUpstreamFailure() {
        when(nuveqVisitorClient.fetchEvents(anyString()))
                .thenThrow(new RuntimeException("connect timed out"));

        listener.pollEvents();

        verify(cardEventListener, never()).onCardEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("events without an id cannot be de-duplicated, so they are skipped")
    void skipsEventsWithoutId() {
        NuveqEventDto noId = new NuveqEventDto();
        noId.setId(null);
        when(nuveqVisitorClient.fetchEvents(anyString())).thenReturn(List.of(noId));

        listener.pollEvents();

        verify(cardEventListener, never()).onCardEvent(org.mockito.ArgumentMatchers.any());
    }
}