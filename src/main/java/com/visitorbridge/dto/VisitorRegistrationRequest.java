package com.visitorbridge.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitorRegistrationRequest {

    @NotBlank(message = "registrationId is required")
    private String registrationId;

    @NotBlank(message = "fullName is required")
    private String fullName;

    @Email(message = "Invalid email format")
    private String email;

    private String phone;

    private String userPhoto;

    private String vehicleNumber;

    @NotNull(message = "visitStart is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime visitStart;

    @NotNull(message = "visitEnd is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime visitEnd;

    @NotNull(message = "roomId is required")
    private Long roomId;

    private Long siteId;

    private Long liftGroupId;

    private List<Long> allowedDoorIds;

    @NotBlank(message = "cardNumber is required")
    private String cardNumber;

    private String checkOutCardNumber;
}
