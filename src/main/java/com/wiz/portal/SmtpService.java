package com.wiz.portal;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;

import com.wiz.session.SeasonConfig;

public class SmtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SeasonConfig config;
    private final MailTransport transport;

    public SmtpService(SeasonConfig config) {
        this(config, (settings, message) -> {
            throw new IllegalStateException("SMTP transport is not configured");
        });
    }

    public SmtpService(SeasonConfig config, MailTransport transport) {
        this.config = config;
        this.transport = transport;
    }

    public boolean configured() {
        return !blank(config.smtpHost()) && !blank(config.smtpSender()) && !blank(config.smtpPassword());
    }

    public String randomCode() {
        return randomCode(6);
    }

    public String randomCode(int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    public MailMessage render(String to, String title, String template, Map<String, ?> values) {
        String html = template == null ? defaultTemplate() : template;
        html = html.replace("{title}", title);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            html = html.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return new MailMessage(to, title, html);
    }

    public void send(MailMessage message) throws IOException {
        if (!configured()) {
            throw new IllegalStateException("SMTP settings are incomplete");
        }
        transport.send(settings(), message);
    }

    public SmtpSettings settings() {
        return new SmtpSettings(
                string(config.smtpHost()),
                config.smtpPort() == null ? 587 : config.smtpPort(),
                string(config.smtpSender()),
                string(config.smtpPassword()));
    }

    private String defaultTemplate() {
        return "<div><h2>{title}</h2>{message}</div>";
    }

    private boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    public record MailMessage(String to, String title, String html) {
    }

    public record SmtpSettings(String host, int port, String sender, String password) {
    }

    @FunctionalInterface
    public interface MailTransport {
        void send(SmtpSettings settings, MailMessage message) throws IOException;
    }
}