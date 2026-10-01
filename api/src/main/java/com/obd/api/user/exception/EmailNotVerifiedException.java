package com.obd.api.user.exception;

import java.util.UUID;

/**
 * The caller has not proved they own their email address, and asked to do
 * something that requires it.
 *
 * A 403 rather than a 404: the caller knows exactly who they are, so there is
 * nothing to hide by pretending the thing does not exist.
 */
public class EmailNotVerifiedException extends RuntimeException {

    private static final String MESSAGE = "User %s has not verified their email address";

    public EmailNotVerifiedException(UUID userId) {
        super(MESSAGE.formatted(userId));
    }
}
