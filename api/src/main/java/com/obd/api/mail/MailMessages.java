package com.obd.api.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two messages this application sends, in Spanish - the only user-facing
 * text in the codebase, which is otherwise English throughout.
 *
 * Plain text rather than HTML on purpose for now: it renders everywhere, it
 * cannot be broken by a mail client's CSS handling, and it is far less likely
 * to be scored as spam than a first HTML template from a young domain.
 *
 * The links are built from configuration, not hard-coded, because where they
 * point changes per environment and is not decided yet: a universal link into
 * the app once the domain exists, a local URL until then.
 */
@Component
public class MailMessages {

    private final String verifyUrlBase;
    private final String resetUrlBase;

    public MailMessages(@Value("${app.mail.verify-url-base}") String verifyUrlBase,
                        @Value("${app.mail.reset-url-base}") String resetUrlBase) {
        this.verifyUrlBase = verifyUrlBase;
        this.resetUrlBase = resetUrlBase;
    }

    public Email verification(String to, String firstName, String rawToken) {
        String link = link(verifyUrlBase, rawToken);
        return new Email(to,
                "Confirmá tu correo - OBIDI",
                """
                Hola %s:

                Para terminar de crear tu cuenta en OBIDI, confirmá que este
                correo es tuyo entrando acá:

                %s

                El enlace vence en 24 horas. Si no creaste esta cuenta, podés
                ignorar este mensaje: sin confirmar, nadie puede usar tu
                dirección para entrar a un grupo.
                """.formatted(firstName, link),
                Email.Purpose.EMAIL_VERIFICATION);
    }

    public Email passwordReset(String to, String firstName, String rawToken) {
        String link = link(resetUrlBase, rawToken);
        return new Email(to,
                "Restablecé tu contraseña - OBIDI",
                """
                Hola %s:

                Pediste restablecer tu contraseña de OBIDI. Podés hacerlo acá:

                %s

                El enlace vence en 30 minutos y se puede usar una sola vez.

                Si no lo pediste vos, no hace falta que hagas nada: tu
                contraseña sigue siendo la misma mientras no uses este enlace.
                """.formatted(firstName, link),
                Email.Purpose.PASSWORD_RESET);
    }

    private static String link(String base, String rawToken) {
        return base.endsWith("/") ? base + rawToken : base + "/" + rawToken;
    }
}
