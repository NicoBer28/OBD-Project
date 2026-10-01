package com.obd.api.support;

import com.obd.api.mail.MailRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the mail a flow asks for, so a test can read the link the way a
 * user reads their inbox.
 *
 * The raw token never leaves the service except inside a published
 * {@link MailRequest} - it is hashed before it reaches the database - so this
 * is the only place a test can see it, which is exactly the point.
 *
 * A plain {@code @EventListener}, not a transactional one: the production
 * listener waits for AFTER_COMMIT, and a repository test's transaction is
 * rolled back, so a transactional listener would never fire here. Mocking
 * ApplicationEventPublisher is not an option either - Spring resolves it to
 * the context itself rather than to a bean, so it cannot be overridden.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordedMail {

    @Bean
    Recorder mailRecorder() {
        return new Recorder();
    }

    public static class Recorder {

        private final List<MailRequest> requests = new ArrayList<>();

        @EventListener
        public void on(MailRequest request) {
            requests.add(request);
        }

        public List<MailRequest> all() {
            return List.copyOf(requests);
        }

        public boolean isEmpty() {
            return requests.isEmpty();
        }

        /** The most recent message, as the user would see it at the top of their inbox. */
        public MailRequest latest() {
            if (requests.isEmpty()) {
                throw new AssertionError("no mail was requested");
            }
            return requests.getLast();
        }

        public String latestToken() {
            return latest().rawToken();
        }

        public void clear() {
            requests.clear();
        }
    }
}
