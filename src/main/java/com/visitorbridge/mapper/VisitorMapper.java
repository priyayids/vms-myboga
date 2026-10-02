package com.visitorbridge.mapper;

import com.visitorbridge.dto.VisitorRegistrationRequest;
import com.visitorbridge.dto.VisitorResponseDto;
import com.visitorbridge.model.UserType;
import com.visitorbridge.model.Visitor;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface VisitorMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "statusEntry", constant = "false")
    @Mapping(target = "nuveqVisitorId", ignore = true)
    @Mapping(target = "nuveqRegistrationId", ignore = true)
    @Mapping(target = "userType", source = "userType")
    @Mapping(target = "allowedDoorIds", source = "request.allowedDoorIds", qualifiedByName = "listToString")
    Visitor toEntity(VisitorRegistrationRequest request, UserType userType);

    @Mapping(target = "allowedDoorIds", source = "allowedDoorIds", qualifiedByName = "stringToList")
    VisitorResponseDto toDto(Visitor visitor);

    @Named("listToString")
    default String listToString(List<Long> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    @Named("stringToList")
    default List<Long> stringToList(String str) {
        if (str == null || str.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }
}
