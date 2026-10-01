package com.obd.api.auth.token.exception;

import com.obd.api.auth.token.TokenKind;
import lombok.Getter;

/**
 * No token matches what arrived. Carries the kind so the answer can be worded
 * for the right flow, and never the token itself - it is a secret, and has no
 * place in a log line or an error body.
 */
@Getter
public class TokenNotFoundException extends RuntimeException {

    private final TokenKind kind;

    public TokenNotFoundException(TokenKind kind) {
        super(kind.notFoundDetail());
        this.kind = kind;
    }
}
