package com.tcgdigital.vmcontrol.dto;

/**
 * DTO for updating user roles.
 */
public class UpdateUserRoleDTO {

    private Boolean admin;
    private Boolean envAdmin;

    public UpdateUserRoleDTO() {
    }

    public UpdateUserRoleDTO(Boolean admin, Boolean envAdmin) {
        this.admin = admin;
        this.envAdmin = envAdmin;
    }

    public Boolean getAdmin() {
        return admin;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public Boolean getEnvAdmin() {
        return envAdmin;
    }

    public void setEnvAdmin(Boolean envAdmin) {
        this.envAdmin = envAdmin;
    }
}

