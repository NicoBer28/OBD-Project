package com.obd.api.group;

/**
 * A member's role inside one group.
 *
 * Distinct from {@link com.obd.api.user.Role}, which is the account's role
 * across the whole system: someone can be a plain USER account and still be the
 * ADMIN of their family group.
 */
public enum GroupRole {
    ADMIN,
    MEMBER
}
