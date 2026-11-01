package com.kasa.analytics;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.security.CurrentTenant;

@RestController
public class AnalyticsController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/v1/analytics/summary")
    public AnalyticsService.Summary summary(Authentication authentication,
                                            @RequestParam(defaultValue = "30") int days,
                                            @RequestParam(defaultValue = "TRY") String currency) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7, 30 or 90");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be a three-letter code");
        }
        return analytics.summary(CurrentTenant.of(authentication), days, currency);
    }
}
