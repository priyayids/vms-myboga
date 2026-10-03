package com.visitorbridge.repository;

import com.visitorbridge.model.Booking;
import com.visitorbridge.model.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, java.util.UUID> {

    Optional<Booking> findByRegistrationId(String registrationId);

    @Query("SELECT b FROM Booking b " +
           "WHERE b.room.id = :roomId " +
           "AND b.bookingStatus IN :statuses " +
           "AND b.visitStart < :end " +
           "AND b.visitEnd > :start")
    List<Booking> findOverlappingBookings(
            @Param("roomId") Long roomId,
            @Param("statuses") List<BookingStatus> statuses,
            @Param("end") OffsetDateTime end,
            @Param("start") OffsetDateTime start
    );

    @Query(value = "SELECT b.* FROM bookings b " +
            "WHERE b.room_id = :roomId " +
            "AND b.booking_status IN (:statuses) " +
            "AND (b.visit_start AT TIME ZONE 'Asia/Jakarta')::date = CAST(:date AS date)",
            nativeQuery = true)
    List<Booking> findActiveBookingsForDate(
            @Param("roomId") Long roomId,
            @Param("statuses") List<String> statuses,
            @Param("date") String date
    );

    @Query("SELECT b FROM Booking b WHERE b.cardNumberIn = :cardNumberIn AND b.bookingStatus = :status")
    List<Booking> findByCardNumberInAndBookingStatus(
            @Param("cardNumberIn") String cardNumberIn,
            @Param("status") BookingStatus status);

    @Query("SELECT b FROM Booking b WHERE b.cardNumberOut = :cardNumberOut AND b.bookingStatus = :status")
    List<Booking> findByCardNumberOutAndBookingStatus(
            @Param("cardNumberOut") String cardNumberOut,
            @Param("status") BookingStatus status);

    @Query("SELECT b FROM Booking b WHERE b.cardNumberIn = :cardNumberIn")
    List<Booking> findAllByCardNumberIn(@Param("cardNumberIn") String cardNumberIn);

    @Query("SELECT b FROM Booking b WHERE b.cardNumberOut = :cardNumberOut")
    List<Booking> findAllByCardNumberOut(@Param("cardNumberOut") String cardNumberOut);

    List<Booking> findByBookingStatus(BookingStatus status);

    List<Booking> findByBookingStatusAndVisitEndBefore(BookingStatus status, OffsetDateTime cutoff);
}

