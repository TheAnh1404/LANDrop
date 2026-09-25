package com.landrop.admin.dto;

import com.landrop.device.dto.DeviceDto;

import java.util.List;

/** Safe administrative view of one user's persistent and runtime identities. */
public record AdminUserIdentityDto(
    AdminUserDto user,
    List<DeviceDto> devices,
    List<AdminSessionDto> sessions,
    List<AdminClientDto> connections
) {}
