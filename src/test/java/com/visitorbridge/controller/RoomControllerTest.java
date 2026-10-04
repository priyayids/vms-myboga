package com.visitorbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.AvailabilityResponseDto;
import com.visitorbridge.dto.AvailabilitySlotDto;
import com.visitorbridge.dto.DoorResponseDto;
import com.visitorbridge.dto.RoomCreateRequest;
import com.visitorbridge.dto.RoomResponseDto;
import com.visitorbridge.dto.RoomSyncResultDto;
import com.visitorbridge.dto.RoomUpdateRequest;
import com.visitorbridge.service.BookingService;
import com.visitorbridge.service.RoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RoomService roomService;

    @MockBean
    private BookingService bookingService;

    @MockBean
    private NuveqVisitorClient nuveqVisitorClient;

    @Test
    @DisplayName("GET /api/rooms - returns list of rooms")
    void testGetAllRooms() throws Exception {
        RoomResponseDto room = RoomResponseDto.builder()
                .id(1L)
                .customName("Meeting Room 1")
                .siteId(167L)
                .doors(List.of(
                        DoorResponseDto.builder().id(10L).nuveqDoorId(2596L).doorName("Demo Door 1").build()
                ))
                .build();

        when(roomService.getAllRooms()).thenReturn(List.of(room));

        mockMvc.perform(get("/api/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].customName").value("Meeting Room 1"))
                .andExpect(jsonPath("$.data[0].doors[0].nuveqDoorId").value(2596));
    }

    @Test
    @DisplayName("POST /api/rooms - 201 Created on valid request")
    void testCreateRoom() throws Exception {
        RoomCreateRequest request = RoomCreateRequest.builder()
                .customName("Boardroom")
                .siteId(167L)
                .expireMinutes(30)
                .doorNuveqIds(List.of(2596L))
                .build();

        RoomResponseDto created = RoomResponseDto.builder()
                .id(5L)
                .customName("Boardroom")
                .siteId(167L)
                .expireMinutes(30)
                .build();

        when(roomService.createRoom(any(RoomCreateRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.customName").value("Boardroom"))
                .andExpect(jsonPath("$.data.expireMinutes").value(30));
    }

    @Test
    @DisplayName("PUT /api/rooms/{id} - 200 OK on update")
    void testUpdateRoom() throws Exception {
        RoomUpdateRequest request = RoomUpdateRequest.builder()
                .customName("Executive Suite")
                .expireMinutes(20)
                .build();

        RoomResponseDto updated = RoomResponseDto.builder()
                .id(5L)
                .customName("Executive Suite")
                .expireMinutes(20)
                .build();

        when(roomService.updateRoom(eq(5L), any(RoomUpdateRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/rooms/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.customName").value("Executive Suite"))
                .andExpect(jsonPath("$.data.expireMinutes").value(20));
    }

    @Test
    @DisplayName("POST /api/rooms/sync - triggers sync from Nuveq")
    void testSyncDoors() throws Exception {
        RoomSyncResultDto syncResult = RoomSyncResultDto.builder()
                .totalFetched(5)
                .createdRooms(3)
                .updatedDoors(2)
                .message("Sync successful")
                .build();

        when(roomService.syncDoorsFromNuveq()).thenReturn(syncResult);

        mockMvc.perform(post("/api/rooms/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalFetched").value(5))
                .andExpect(jsonPath("$.data.createdRooms").value(3));
    }

    @Test
    @DisplayName("GET /api/rooms/doors - returns all doors")
    void testGetAllDoors() throws Exception {
        DoorResponseDto door = DoorResponseDto.builder()
                .id(1L)
                .nuveqDoorId(2596L)
                .doorName("Demo Door 1")
                .roomId(10L)
                .roomCustomName("not mapped")
                .build();

        when(roomService.getAllDoors()).thenReturn(List.of(door));

        mockMvc.perform(get("/api/rooms/doors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].nuveqDoorId").value(2596))
                .andExpect(jsonPath("$.data[0].roomCustomName").value("not mapped"));
    }

    @Test
    @DisplayName("GET /api/rooms/{roomId}/availability - returns hourly slot grid")
    void testGetAvailability() throws Exception {
        AvailabilityResponseDto availability = AvailabilityResponseDto.builder()
                .roomId(5L)
                .roomName("Meeting Room Alpha")
                .date("2026-10-03")
                .slots(List.of(
                        AvailabilitySlotDto.builder().hour(9).available(true).build(),
                        AvailabilitySlotDto.builder().hour(10).available(false).bookedBy("REG-20261003-001").build(),
                        AvailabilitySlotDto.builder().hour(11).available(false).bookedBy("REG-20261003-001").build(),
                        AvailabilitySlotDto.builder().hour(12).available(true).build()
                ))
                .build();

        when(bookingService.getAvailability(5L, "2026-10-03")).thenReturn(availability);

        mockMvc.perform(get("/api/rooms/5/availability").param("date", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value(5))
                .andExpect(jsonPath("$.data.date").value("2026-10-03"))
                .andExpect(jsonPath("$.data.slots.length()").value(4))
                .andExpect(jsonPath("$.data.slots[0].hour").value(9))
                .andExpect(jsonPath("$.data.slots[0].available").value(true))
                .andExpect(jsonPath("$.data.slots[1].available").value(false))
                .andExpect(jsonPath("$.data.slots[1].bookedBy").value("REG-20261003-001"));
    }
}
