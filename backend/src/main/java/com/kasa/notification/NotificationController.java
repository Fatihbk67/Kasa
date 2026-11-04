package com.kasa.notification;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

/** For people signed in to the dashboard. An API key belongs to no person, so it has no inbox. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationService.Inbox inbox(Authentication authentication) {
        return notifications.inbox(CurrentTenant.of(authentication), CurrentTenant.userId(authentication));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(Authentication authentication, @PathVariable UUID id) {
        notifications.markRead(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(Authentication authentication) {
        notifications.markAllRead(CurrentTenant.of(authentication), CurrentTenant.userId(authentication));
    }
}
