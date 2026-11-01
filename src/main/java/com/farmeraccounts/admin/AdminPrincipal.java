package com.farmeraccounts.admin;

import java.util.UUID;

public record AdminPrincipal(UUID id, String username, String role, String ip) {

    public boolean isOwner() {
        return "OWNER".equals(role);
    }
}
