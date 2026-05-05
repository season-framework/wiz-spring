package com.wiz.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.wiz.runtime.ConfigNamespace;
import com.wiz.session.SeasonConfig;

import org.junit.jupiter.api.Test;

class SmtpServiceTest {

    @Test
    void rendersTemplateAndGeneratesNumericCodes() {
        SmtpService smtp = new SmtpService(SeasonConfig.from(new ConfigNamespace("season", SeasonConfig.defaults())));

        SmtpService.MailMessage message = smtp.render(
                "user@example.com",
                "Welcome",
                "<h1>{title}</h1><p>{message}</p>",
                Map.of("message", "Hello"));

        assertFalse(smtp.configured());
        assertEquals("user@example.com", message.to());
        assertEquals("<h1>Welcome</h1><p>Hello</p>", message.html());
        assertTrue(smtp.randomCode(8).matches("\\d{8}"));
        assertThrows(IllegalArgumentException.class, () -> smtp.randomCode(0));
    }

    @Test
    void sendsThroughInjectedTransportWhenConfigured() throws Exception {
        Map<String, Object> values = new java.util.LinkedHashMap<>(SeasonConfig.defaults());
        values.put("smtp_host", "smtp.example.com");
        values.put("smtp_port", 2525);
        values.put("smtp_sender", "noreply@example.com");
        values.put("smtp_password", "secret");
        AtomicReference<SmtpService.SmtpSettings> capturedSettings = new AtomicReference<>();
        AtomicReference<SmtpService.MailMessage> capturedMessage = new AtomicReference<>();
        SmtpService smtp = new SmtpService(
                SeasonConfig.from(new ConfigNamespace("season", values)),
                (settings, message) -> {
                    capturedSettings.set(settings);
                    capturedMessage.set(message);
                });

        smtp.send(new SmtpService.MailMessage("to@example.com", "Title", "Body"));

        assertTrue(smtp.configured());
        assertEquals("smtp.example.com", capturedSettings.get().host());
        assertEquals(2525, capturedSettings.get().port());
        assertEquals("Title", capturedMessage.get().title());
    }
}