package com.obd.api.auth.token;


public enum TokenKind {

    VERIFICATION("No such verification link", "That verification link is no longer valid"),
    RESET("No such password reset link", "That password reset link is no longer valid");

    private final String notFound;
    private final String noLongerValid;

    TokenKind(String notFound, String noLongerValid) {
        this.notFound = notFound;
        this.noLongerValid = noLongerValid;
    }

    public String notFoundDetail() { return notFound; }
    public String noLongerValidDetail() { return noLongerValid; }
}
