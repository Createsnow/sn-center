package com.adtek.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(requiredProperties = {"departments", "roles"})
public record UserOptionsVO(List<Department> departments, List<Role> roles) {
    @Schema(requiredProperties = {"id", "name"})
    public record Department(String id, String name) {}

    @Schema(requiredProperties = {"id", "name", "dataScope"})
    public record Role(String id, String name, int dataScope) {}
}
