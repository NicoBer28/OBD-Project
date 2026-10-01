package com.obd.api.mail;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * What ResendMailer hands the provider, with the SDK mocked so nothing leaves
 * the machine.
 *
 * {@code Emails} is final; Mockito's inline mock maker handles that, which is
 * the agent wired into Surefire in the pom.
 */
class ResendMailerTest {

    private static final String FROM = "no-reply@mail.obidi.com.ar";

    private Resend resend;
    private Emails emails;
    private ResendMailer mailer;

    @BeforeEach
    void setUp() {
        resend = mock(Resend.class);
        emails = mock(Emails.class);
        given(resend.emails()).willReturn(emails);
        mailer = new ResendMailer(resend, FROM, "OBIDI");
    }

    private static Email verification() {
        return new Email("ada@example.com", "Confirmá tu correo - OBIDI",
                "Entrá acá:\nhttps://obidi.com.ar/verify-email/tok-123\n",
                Email.Purpose.EMAIL_VERIFICATION);
    }

    private CreateEmailOptions captureSend() throws ResendException {
        var captor = ArgumentCaptor.forClass(CreateEmailOptions.class);
        org.mockito.Mockito.verify(emails).send(captor.capture(), any(RequestOptions.class));
        return captor.getValue();
    }

    @Test
    void theMessageIsHandedOverWholeAsPlainText() throws Exception {
        given(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                .willReturn(new CreateEmailResponse("re_123"));

        mailer.send(verification());

        CreateEmailOptions sent = captureSend();
        assertThat(sent.getTo()).containsExactly("ada@example.com");
        assertThat(sent.getSubject()).isEqualTo("Confirmá tu correo - OBIDI");
        assertThat(sent.getText()).contains("https://obidi.com.ar/verify-email/tok-123");
        // Plain text only - no HTML alternative to be mangled or scored.
        assertThat(sent.getHtml()).isNull();
    }

    @Test
    void theSenderCarriesTheFriendlyName() throws Exception {
        given(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                .willReturn(new CreateEmailResponse("re_123"));

        mailer.send(verification());

        // The form Resend wants for a display name.
        assertThat(captureSend().getFrom()).isEqualTo("OBIDI <no-reply@mail.obidi.com.ar>");
    }

    @Test
    void aBlankNameLeavesABareAddress() {
        assertThat(ResendMailer.senderAddress(FROM, "   ")).isEqualTo(FROM);
        assertThat(ResendMailer.senderAddress(FROM, null)).isEqualTo(FROM);
        assertThat(ResendMailer.senderAddress("  " + FROM + " ", " OBIDI "))
                .isEqualTo("OBIDI <" + FROM + ">");
    }

    @Test
    void theIdempotencyKeyIsStableForTheSameMessageAndDiffersForAnother() throws Exception {
        given(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                .willReturn(new CreateEmailResponse("re_123"));

        mailer.send(verification());
        mailer.send(verification());
        Email other = new Email("ada@example.com", "Restablecé tu contraseña - OBIDI",
                "Entrá acá:\nhttps://obidi.com.ar/reset-password/tok-456\n",
                Email.Purpose.PASSWORD_RESET);
        mailer.send(other);

        var captor = ArgumentCaptor.forClass(RequestOptions.class);
        org.mockito.Mockito.verify(emails, org.mockito.Mockito.times(3))
                .send(any(CreateEmailOptions.class), captor.capture());

        String first = captor.getAllValues().get(0).getIdempotencyKey();
        String second = captor.getAllValues().get(1).getIdempotencyKey();
        String third = captor.getAllValues().get(2).getIdempotencyKey();

        assertThat(first).isNotBlank();
        // Same message twice: Resend collapses it instead of delivering two
        // copies to somebody's inbox.
        assertThat(second).isEqualTo(first);
        // A different link is a different message and must still go out.
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void aProviderFailureThrowsWithoutLeakingTheLink() throws Exception {
        given(emails.send(any(CreateEmailOptions.class), any(RequestOptions.class)))
                .willThrow(new ResendException("403 the domain mail.obidi.com.ar is not verified"));

        assertThatThrownBy(() -> mailer.send(verification()))
                .isInstanceOf(MailSendFailedException.class)
                .hasMessageContaining("EMAIL_VERIFICATION")
                .hasMessageContaining("ada@example.com")
                // The token must not travel in an exception message, which
                // ends up in logs and crash reports.
                .hasMessageNotContaining("tok-123");
    }

    @Test
    void theApiKeyIsRequiredAtStartupNotAtTheFirstSend() {
        // A deployment configured for Resend with no key is broken; the only
        // good moment to find out is before it serves traffic.
        assertThatThrownBy(() -> new ResendMailer("", FROM, "OBIDI", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mail.resend.api-key");

        assertThatThrownBy(() -> new ResendMailer(null, FROM, "OBIDI", 10))
                .isInstanceOf(IllegalStateException.class);
    }
}
