package com.obd.api.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link Mailer} a given configuration actually gets.
 *
 * Worth its own test because getting this wrong does not fail a unit test, it
 * fails at startup - two candidates and an ambiguous injection, or none and a
 * missing bean - and in the worst case it quietly selects the logging mailer
 * in production, where printing a live token to the log throws away the whole
 * point of storing only its hash.
 */
class MailProviderSelectionTest {

    // The two implementations are registered as component classes, not as
    // @Bean methods: a @Bean method would create them unconditionally and
    // bypass the class-level @ConditionalOnProperty that is the thing under
    // test here.
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(LoggingMailer.class, ResendMailer.class)
            .withPropertyValues(
                    "app.mail.from=no-reply@mail.obidi.com.ar",
                    "app.mail.from-name=OBIDI",
                    // LoggingMailer refuses to exist in a run that serves real
                    // traffic, so every case below has to say which kind of run
                    // this is. The one that leaves it out is the guard's own
                    // test at the bottom.
                    "spring.profiles.active=test");

    @Test
    void withNoProviderSetNothingIsSent() {
        // The default everywhere except production: LoggingMailer answers, so
        // the suite and the smoke script need no account and no network.
        contexts.run(context -> assertThat(context)
                .hasSingleBean(Mailer.class)
                .hasSingleBean(LoggingMailer.class)
                .doesNotHaveBean(ResendMailer.class));
    }

    @Test
    void logIsSpeltOutTheSameWay() {
        contexts.withPropertyValues("app.mail.provider=log")
                .run(context -> assertThat(context)
                        .hasSingleBean(Mailer.class)
                        .hasSingleBean(LoggingMailer.class));
    }

    @Test
    void resendReplacesItEntirely() {
        contexts.withPropertyValues(
                        "app.mail.provider=resend",
                        "app.mail.resend.api-key=re_test_notarealkey")
                .run(context -> assertThat(context)
                        // Exactly one: the two implementations are mutually
                        // exclusive, so nothing is ambiguous to inject.
                        .hasSingleBean(Mailer.class)
                        .hasSingleBean(ResendMailer.class)
                        .doesNotHaveBean(LoggingMailer.class));
    }

    /**
     * The guard that stops a production deployment from printing live tokens
     * to its log - and, since the email gate became hard, from locking out
     * every account that registers, because nobody can confirm an address
     * whose mail was never sent.
     */
    @Test
    void theLoggingMailerRefusesToStartWhereTrafficIsReal() {
        new ApplicationContextRunner()
                .withUserConfiguration(LoggingMailer.class, ResendMailer.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("app.mail.provider is 'log'"));
    }

    @Test
    void butResendIsFineThere() {
        // The same run with a real provider configured starts normally: the
        // guard is about the logging mailer, not about profiles.
        new ApplicationContextRunner()
                .withUserConfiguration(LoggingMailer.class, ResendMailer.class)
                .withPropertyValues(
                        "app.mail.from=no-reply@mail.obidi.com.ar",
                        "app.mail.from-name=OBIDI",
                        "app.mail.provider=resend",
                        "app.mail.resend.api-key=re_test_notarealkey")
                .run(context -> assertThat(context)
                        .hasSingleBean(ResendMailer.class)
                        .doesNotHaveBean(LoggingMailer.class));
    }

    @Test
    void resendWithNoKeyRefusesToStart() {
        contexts.withPropertyValues("app.mail.provider=resend")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("app.mail.resend.api-key"));
    }
}
