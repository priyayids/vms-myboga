package com.visitorbridge.repository;

import com.visitorbridge.model.UserType;
import com.visitorbridge.model.Visitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitorRepository extends JpaRepository<Visitor, UUID> {

    List<Visitor> findByRegistrationId(String registrationId);

    List<Visitor> findByRegistrationIdIn(List<String> registrationIds);

    @Query("SELECT v FROM Visitor v WHERE v.registrationId = :regId OR v.registrationId LIKE CONCAT(:regId, '_%')")
    List<Visitor> findAllByBaseRegistrationId(@Param("regId") String regId);

    Optional<Visitor> findByRegistrationIdAndUserType(String registrationId, UserType userType);

    List<Visitor> findByCardNumber(String cardNumber);

    Optional<Visitor> findByCardNumberAndUserType(String cardNumber, UserType userType);

    @Modifying
    @Query("UPDATE Visitor v SET v.statusEntry = true, v.updatedAt = :now WHERE v.cardNumber = :cardNumber AND v.userType = :userType AND v.statusEntry = false")
    int markStatusEntryByCardAndType(@Param("cardNumber") String cardNumber, @Param("userType") UserType userType, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE Visitor v SET v.statusEntry = true, v.updatedAt = :now WHERE v.id = :id AND v.statusEntry = false")
    int markStatusEntryById(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
