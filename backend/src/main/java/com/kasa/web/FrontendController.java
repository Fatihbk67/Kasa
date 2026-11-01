package com.kasa.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends the single-page app's routes to its entry file, so a reload or shared link works on any page. */
@Controller
public class FrontendController {

    @GetMapping({"/login", "/register", "/pay/{id}", "/analytics", "/payments", "/payments/{id}", "/payouts", "/payouts/{id}",
            "/disputes", "/disputes/{id}", "/customers", "/customers/{id}", "/payment-links", "/l/{slug}",
            "/developers", "/team", "/audit", "/settings", "/invite/{token}", "/admin", "/admin/reconciliation/{id}"})
    public String frontendRoute() {
        return "forward:/index.html";
    }
}
