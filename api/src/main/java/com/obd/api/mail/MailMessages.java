package com.obd.api.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

                Hasta que lo confirmes, la cuenta no se puede usar: no vas a
                poder iniciar sesión ni cargar autos, grupos o viajes.

                El enlace vence en 24 horas. Si vence, pedí uno nuevo desde la
                pantalla de inicio de sesión de la app.

                ¿No creaste esta cuenta? Entonces alguien escribió tu dirección
                al registrarse. NO entres al enlace: mientras nadie lo use, esa
                cuenta no sirve para nada, y si entrás vos la estarías
                habilitando con una contraseña que eligió otra persona. La
                dirección es tuya, así que podés quedártela en cualquier
                momento con «olvidé mi contraseña» desde la app, lo que cierra
                todas las sesiones de quien la creó.
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
