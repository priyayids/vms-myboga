package com.visitorbridge.controller;

import com.visitorbridge.dto.ApiResponse;
import com.visitorbridge.dto.AvailabilityResponseDto;
import com.visitorbridge.dto.DoorResponseDto;
import com.visitorbridge.dto.RoomCreateRequest;
import com.visitorbridge.dto.RoomResponseDto;
import com.visitorbridge.dto.RoomSyncResultDto;
import com.visitorbridge.dto.RoomUpdateRequest;
import com.visitorbridge.service.BookingService;
import com.visitorbridge.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final BookingService bookingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoomResponseDto>>> getAllRooms() {
        List<RoomResponseDto> list = roomService.getAllRooms();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RoomResponseDto>> getRoomById(@PathVariable("id") Long id) {
        RoomResponseDto room = roomService.getRoomById(id);
        return ResponseEntity.ok(ApiResponse.success(room));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RoomResponseDto>> createRoom(@Valid @RequestBody RoomCreateRequest request) {
        RoomResponseDto created = roomService.createRoom(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Room created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RoomResponseDto>> updateRoom(
            @PathVariable("id") Long id,
            @Valid @RequestBody RoomUpdateRequest request) {
        RoomResponseDto updated = roomService.updateRoom(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Room updated successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(@PathVariable("id") Long id) {
        roomService.deleteRoom(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Room deleted successfully"));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<RoomSyncResultDto>> syncDoors() {
        RoomSyncResultDto result = roomService.syncDoorsFromNuveq();
        return ResponseEntity.ok(ApiResponse.success(result, "Doors synchronized successfully"));
    }

    @GetMapping("/doors")
    public ResponseEntity<ApiResponse<List<DoorResponseDto>>> getAllDoors() {
        List<DoorResponseDto> doors = roomService.getAllDoors();
        return ResponseEntity.ok(ApiResponse.success(doors));
    }

    @GetMapping("/{roomId}/availability")
    public ResponseEntity<ApiResponse<AvailabilityResponseDto>> getAvailability(
            @PathVariable("roomId") Long roomId,
            @RequestParam("date") String date) {
        AvailabilityResponseDto availability = bookingService.getAvailability(roomId, date);
        return ResponseEntity.ok(ApiResponse.success(availability));
    }
}
