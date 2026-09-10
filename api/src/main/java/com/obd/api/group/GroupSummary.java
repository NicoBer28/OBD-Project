package com.obd.api.group;

public record GroupSummary(
        Group group,
        GroupRole callerRole,
        long memberCount
) {}
