package com.visitorbridge.service;

import com.visitorbridge.dto.RoomSyncResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomStartupSyncRunner implements ApplicationRunner {

    private final RoomService roomService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("Running startup synchronization of doors from Nuveq...");
            RoomSyncResultDto result = roomService.syncDoorsFromNuveq();
            if (result != null) {
                log.info("Startup door synchronization successful: {}", result.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Startup door synchronization from Nuveq was skipped or failed: {}", ex.getMessage());
        }
    }
}
