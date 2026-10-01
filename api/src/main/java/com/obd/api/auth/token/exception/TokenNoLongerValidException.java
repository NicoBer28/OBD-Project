package com.obd.api.auth.token.exception;

import com.obd.api.auth.token.TokenKind;
import lombok.Getter;

/**
 * The token exists but cannot be redeemed: already used, or expired.
 *
 * Told apart from "no such token" on purpose - only someone holding a real
 * link gets this far, and the two need different words in the app ("ese
 * enlace no es válido" against "pedí uno nuevo").
 */
@Getter
public class TokenNoLongerValidException extends RuntimeException {

    private final TokenKind kind;

    public TokenNoLongerValidException(TokenKind kind) {
        super(kind.noLongerValidDetail());
        this.kind = kind;
    }
}
