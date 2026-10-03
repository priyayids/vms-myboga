package com.visitorbridge.service;

import com.visitorbridge.client.NuveqDoorDto;
import com.visitorbridge.client.NuveqVisitorClient;
import com.visitorbridge.dto.DoorResponseDto;
import com.visitorbridge.dto.RoomCreateRequest;
import com.visitorbridge.dto.RoomResponseDto;
import com.visitorbridge.dto.RoomSyncResultDto;
import com.visitorbridge.dto.RoomUpdateRequest;
import com.visitorbridge.exception.ResourceNotFoundException;
import com.visitorbridge.model.Door;
import com.visitorbridge.model.Room;
import com.visitorbridge.repository.DoorRepository;
import com.visitorbridge.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final DoorRepository doorRepository;
    private final NuveqVisitorClient nuveqVisitorClient;
    private final TransactionLogger transactionLogger;

    @Transactional
    public RoomSyncResultDto syncDoorsFromNuveq() {
        log.info("Initiating sync of doors from Nuveq Partner API...");
        List<NuveqDoorDto> doorsFromNuveq = nuveqVisitorClient.fetchDoors();
        int total = doorsFromNuveq.size();
        int createdRooms = 0;
        int updatedDoors = 0;

        for (NuveqDoorDto dto : doorsFromNuveq) {
            if (dto.getId() == null) {
                continue;
            }

            Optional<Door> existingDoorOpt = doorRepository.findByNuveqDoorId(dto.getId());
            if (existingDoorOpt.isEmpty()) {
                // Create a default Room instance with autoincrement ID and "not mapped" name
                Room room = Room.builder()
                        .customName("not mapped")
                        .siteId(dto.getSiteId())
                        .build();
                room = roomRepository.save(room);
                createdRooms++;

                Door door = Door.builder()
                        .nuveqDoorId(dto.getId())
                        .doorName(dto.getName() != null ? dto.getName() : "Door " + dto.getId())
                        .doorNumber(dto.getDoorNumber())
                        .controllerId(dto.getControllerId())
                        .siteId(dto.getSiteId())
                        .room(room)
                        .build();
                doorRepository.save(door);
            } else {
                Door door = existingDoorOpt.get();
                door.setDoorName(dto.getName() != null ? dto.getName() : door.getDoorName());
                door.setDoorNumber(dto.getDoorNumber());
                door.setControllerId(dto.getControllerId());
                door.setSiteId(dto.getSiteId());

                if (door.getRoom() == null) {
                    Room room = Room.builder()
                            .customName("not mapped")
                            .siteId(dto.getSiteId())
                            .build();
                    room = roomRepository.save(room);
                    door.setRoom(room);
                    createdRooms++;
                }

                doorRepository.save(door);
                updatedDoors++;
            }
        }

        log.info("Doors synchronization completed: total={}, createdRooms={}, updatedDoors={}",
                total, createdRooms, updatedDoors);
        transactionLogger.logTransaction("RoomService", "SYSTEM", "SYNC_DOORS", "SUCCESS",
                "total=" + total + " createdRooms=" + createdRooms + " updatedDoors=" + updatedDoors);

        return RoomSyncResultDto.builder()
                .totalFetched(total)
                .createdRooms(createdRooms)
                .updatedDoors(updatedDoors)
                .message("Successfully synchronized " + total + " doors from Nuveq")
                .build();
    }

    @Transactional(readOnly = true)
    public List<RoomResponseDto> getAllRooms() {
        return roomRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toRoomResponseDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoomResponseDto getRoomById(Long id) {
        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + id));
        return toRoomResponseDto(room);
    }

    @Transactional
    public RoomResponseDto createRoom(RoomCreateRequest request) {
        Room room = Room.builder()
                .customName(request.getCustomName())
                .siteId(request.getSiteId())
                .liftGroupId(request.getLiftGroupId() != null ? request.getLiftGroupId() : 630L)
                .doors(new ArrayList<>())
                .build();
        room = roomRepository.save(room);

        if (request.getDoorNuveqIds() != null && !request.getDoorNuveqIds().isEmpty()) {
            List<Door> doors = doorRepository.findByNuveqDoorIdIn(request.getDoorNuveqIds());
            for (Door d : doors) {
                d.setRoom(room);
                doorRepository.save(d);
            }
            room.setDoors(doors);
        }

        return toRoomResponseDto(room);
    }

    @Transactional
    public RoomResponseDto updateRoom(Long id, RoomUpdateRequest request) {
        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + id));

        if (request.getCustomName() != null && !request.getCustomName().isBlank()) {
            room.setCustomName(request.getCustomName());
        }
        if (request.getSiteId() != null) {
            room.setSiteId(request.getSiteId());
        }
        if (request.getLiftGroupId() != null) {
            room.setLiftGroupId(request.getLiftGroupId());
        }

        if (request.getDoorNuveqIds() != null) {
            // Unlink current doors not in the new list
            List<Door> currentDoors = doorRepository.findByRoomId(id);
            for (Door current : currentDoors) {
                if (!request.getDoorNuveqIds().contains(current.getNuveqDoorId())) {
                    current.setRoom(null);
                    doorRepository.save(current);
                }
            }
            // Link new doors
            if (!request.getDoorNuveqIds().isEmpty()) {
                List<Door> newDoors = doorRepository.findByNuveqDoorIdIn(request.getDoorNuveqIds());
                for (Door d : newDoors) {
                    d.setRoom(room);
                    doorRepository.save(d);
                }
                room.setDoors(newDoors);
            } else {
                room.setDoors(new ArrayList<>());
            }
        }

        room = roomRepository.save(room);
        return toRoomResponseDto(room);
    }

    @Transactional
    public void deleteRoom(Long id) {
        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + id));

        List<Door> doors = doorRepository.findByRoomId(id);
        for (Door d : doors) {
            d.setRoom(null);
            doorRepository.save(d);
        }

        roomRepository.delete(room);
    }

    @Transactional(readOnly = true)
    public List<DoorResponseDto> getAllDoors() {
        return doorRepository.findAllByOrderByNuveqDoorIdAsc().stream()
                .map(this::toDoorResponseDto)
                .toList();
    }

    private RoomResponseDto toRoomResponseDto(Room room) {
        List<Door> doors = doorRepository.findByRoomId(room.getId());
        List<DoorResponseDto> doorDtos = (doors != null)
                ? doors.stream().map(this::toDoorResponseDto).toList()
                : Collections.emptyList();

        return RoomResponseDto.builder()
                .id(room.getId())
                .customName(room.getCustomName())
                .siteId(room.getSiteId())
                .liftGroupId(room.getLiftGroupId())
                .doors(doorDtos)
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt())
                .build();
    }

    private DoorResponseDto toDoorResponseDto(Door door) {
        return DoorResponseDto.builder()
                .id(door.getId())
                .nuveqDoorId(door.getNuveqDoorId())
                .doorName(door.getDoorName())
                .doorNumber(door.getDoorNumber())
                .controllerId(door.getControllerId())
                .siteId(door.getSiteId())
                .roomId(door.getRoom() != null ? door.getRoom().getId() : null)
                .roomCustomName(door.getRoom() != null ? door.getRoom().getCustomName() : null)
                .build();
    }
}
