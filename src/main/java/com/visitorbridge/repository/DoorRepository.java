package com.visitorbridge.repository;

import com.visitorbridge.model.Door;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DoorRepository extends JpaRepository<Door, Long> {

    Optional<Door> findByNuveqDoorId(Long nuveqDoorId);

    List<Door> findByRoomId(Long roomId);

    List<Door> findByRoomIsNull();

    List<Door> findByNuveqDoorIdIn(List<Long> nuveqDoorIds);

    List<Door> findAllByOrderByNuveqDoorIdAsc();
}
