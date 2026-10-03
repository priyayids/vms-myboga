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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private DoorRepository doorRepository;

    @Mock
    private NuveqVisitorClient nuveqVisitorClient;

    @Mock
    private TransactionLogger transactionLogger;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(roomRepository, doorRepository, nuveqVisitorClient, transactionLogger);
    }

    @Test
    @DisplayName("syncDoorsFromNuveq: inserts new doors and creates default 'not mapped' Room instances")
    void testSyncDoors_CreatesDefaultRooms() {
        NuveqDoorDto door1 = NuveqDoorDto.builder()
                .id(2596L)
                .name("Demo Door 1")
                .doorNumber(1)
                .controllerId(1273L)
                .siteId(167L)
                .build();
        NuveqDoorDto door2 = NuveqDoorDto.builder()
                .id(4904L)
                .name("5601 Door1")
                .doorNumber(1)
                .controllerId(2489L)
                .siteId(167L)
                .build();

        when(nuveqVisitorClient.fetchDoors()).thenReturn(List.of(door1, door2));
        when(doorRepository.findByNuveqDoorId(2596L)).thenReturn(Optional.empty());
        when(doorRepository.findByNuveqDoorId(4904L)).thenReturn(Optional.empty());

        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(10L);
            }
            return r;
        });

        when(doorRepository.save(any(Door.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomSyncResultDto result = roomService.syncDoorsFromNuveq();

        assertThat(result.getTotalFetched()).isEqualTo(2);
        assertThat(result.getCreatedRooms()).isEqualTo(2);

        ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
        verify(roomRepository, times(2)).save(roomCaptor.capture());
        assertThat(roomCaptor.getAllValues()).allMatch(r -> "not mapped".equals(r.getCustomName()));

        ArgumentCaptor<Door> doorCaptor = ArgumentCaptor.forClass(Door.class);
        verify(doorRepository, times(2)).save(doorCaptor.capture());
        assertThat(doorCaptor.getAllValues()).hasSize(2);
        assertThat(doorCaptor.getAllValues().get(0).getNuveqDoorId()).isEqualTo(2596L);
        assertThat(doorCaptor.getAllValues().get(1).getNuveqDoorId()).isEqualTo(4904L);
    }

    @Test
    @DisplayName("createRoom: creates new room and maps doors if provided")
    void testCreateRoom_WithDoors() {
        RoomCreateRequest request = RoomCreateRequest.builder()
                .customName("Meeting Room Alpha")
                .siteId(167L)
                .doorNuveqIds(List.of(2596L, 4904L))
                .build();

        Door d1 = Door.builder().id(1L).nuveqDoorId(2596L).doorName("Demo Door 1").build();
        Door d2 = Door.builder().id(2L).nuveqDoorId(4904L).doorName("5601 Door1").build();

        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(101L);
            return r;
        });
        when(doorRepository.findByNuveqDoorIdIn(List.of(2596L, 4904L))).thenReturn(List.of(d1, d2));
        when(doorRepository.findByRoomId(101L)).thenReturn(List.of(d1, d2));

        RoomResponseDto response = roomService.createRoom(request);

        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getCustomName()).isEqualTo("Meeting Room Alpha");
        assertThat(response.getDoors()).hasSize(2);
        assertThat(d1.getRoom()).isNotNull();
        assertThat(d2.getRoom()).isNotNull();
    }

    @Test
    @DisplayName("updateRoom: updates custom name and reassigns doors")
    void testUpdateRoom() {
        Room existingRoom = Room.builder()
                .id(50L)
                .customName("not mapped")
                .siteId(167L)
                .doors(new ArrayList<>())
                .build();

        Door oldDoor = Door.builder().id(1L).nuveqDoorId(1111L).doorName("Old Door").room(existingRoom).build();
        Door newDoor = Door.builder().id(2L).nuveqDoorId(2222L).doorName("New Door").build();

        when(roomRepository.findById(50L)).thenReturn(Optional.of(existingRoom));
        when(doorRepository.findByRoomId(50L)).thenReturn(List.of(oldDoor));
        when(doorRepository.findByNuveqDoorIdIn(List.of(2222L))).thenReturn(List.of(newDoor));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomUpdateRequest updateRequest = RoomUpdateRequest.builder()
                .customName("VIP Lounge")
                .doorNuveqIds(List.of(2222L))
                .build();

        RoomResponseDto updated = roomService.updateRoom(50L, updateRequest);

        assertThat(updated.getCustomName()).isEqualTo("VIP Lounge");
        assertThat(oldDoor.getRoom()).isNull();
        assertThat(newDoor.getRoom()).isEqualTo(existingRoom);
    }

    @Test
    @DisplayName("getRoomById: throws ResourceNotFoundException if room not found")
    void testGetRoomById_NotFound() {
        when(roomRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoomById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Room not found with id: 999");
    }

    @Test
    @DisplayName("deleteRoom: unlinks assigned doors and deletes room")
    void testDeleteRoom() {
        Room room = Room.builder().id(30L).customName("To Delete").build();
        Door door = Door.builder().id(5L).nuveqDoorId(3509L).room(room).build();

        when(roomRepository.findById(30L)).thenReturn(Optional.of(room));
        when(doorRepository.findByRoomId(30L)).thenReturn(List.of(door));

        roomService.deleteRoom(30L);

        assertThat(door.getRoom()).isNull();
        verify(doorRepository).save(door);
        verify(roomRepository).delete(room);
    }
}
