package com.engly.identity.api.dto;

import com.engly.identity.domain.UserAccount;

/** Identity projection returned to the SPA by /api/me and the auth endpoints. */
public record UserInfoResponse(String sub, String email, String name, boolean isAdmin) {

    public static UserInfoResponse from(UserAccount user) {
        return new UserInfoResponse(user.getSub(), user.getEmail(), user.getDisplayName(), user.isAdmin());
    }
}
