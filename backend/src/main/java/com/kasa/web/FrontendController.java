package com.kasa.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends the single-page app's routes to its entry file, so a reload or shared link works on any page. */
@Controller
public class FrontendController {

    @GetMapping({"/login", "/register", "/pay/{id}", "/analytics", "/payments", "/payments/{id}", "/payouts", "/payouts/{id}",
            "/developers", "/admin", "/admin/reconciliation/{id}"})
    public String frontendRoute() {
        return "forward:/index.html";
    }
}
