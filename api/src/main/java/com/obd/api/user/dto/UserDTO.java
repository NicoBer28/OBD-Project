package com.obd.api.user.dto;


import com.obd.api.user.User;
import jakarta.validation.constraints.*;

import java.util.UUID;


public class UserDTO {
    public record Create(
            @NotBlank String userName,
            @NotBlank String userLastName,
            @NotBlank @Email String userEmail,
            @NotBlank @Size(min = 8, max = 72) String userPassword,
            @Pattern(regexp = PHONE, message = "Invalid Phone Number") String userPhone
    ) {}

    public record Update(
            @NotBlank String userName,
            @NotBlank String userLastName,
            @Pattern(regexp = PHONE, message = "Invalid Phone Number") String userPhone
    ) {}

    public record ChangePassword(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword
    ){}

    public record VerifyEmail(
            @NotBlank String token
    ){}

    /**
     * Who to send another verification link to, if anyone owns the address and
     * has not confirmed it yet.
     */
    public record ResendVerification(
            @NotBlank @Email String userEmail
    ){}

    public record ForgotPassword(
            @NotBlank @Email String userEmail
    ){}

    public record ResetPassword(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 72) String newPassword
    ){}

    public record Login(
            @NotBlank @Email String userEmail,
            @NotBlank @Size(min = 8, max = 72) String userPassword
    ){}

    public record Read(
            UUID id,
            String userName,
            String userLastName,
            String userEmail,
            String userPhone,
            boolean emailVerified
    ) {
        public static Read from(User u) {
            return new Read(u.getUserId(), u.getUserName(), u.getUserLastName(),
                    u.getUserEmail(), u.getUserPhone(), u.getUserEmailVerifiedAt() != null);
        }
    }

    private static final String PHONE =
            "^\\+?(\\d{1,3})?[-.\\s]?\\(?\\d{2,4}\\)?[-.\\s]?\\d{3,4}[-.\\s]?\\d{3,4}$";
}
