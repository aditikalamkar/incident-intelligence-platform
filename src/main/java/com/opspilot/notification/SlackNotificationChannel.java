package com.opspilot.notification;

import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Component
class SlackNotificationChannel implements NotificationChannel {

    /** Short, because the dispatcher holds the notification's row lock while sending. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;
    private final JsonMapper json;

    SlackNotificationChannel(JsonMapper json) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(requestFactory).build();
        this.json = json;
    }

    @Override
    public Channel type() {
        return Channel.SLACK;
    }

    @Override
    public void send(PendingNotification notification) {
        http.post()
                .uri(notification.target())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("text", notification.message())))
                .retrieve()
                .toBodilessEntity();
    }
}
