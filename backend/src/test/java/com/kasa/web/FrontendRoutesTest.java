package com.kasa.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.kasa.support.PostgresIntegrationTest;

/**
 * Every page the web app routes to must also be handed to the app by the server, or a link in an email or a
 * reload of that page answers 404. The list is read from the app's own router, so a new page cannot be forgotten.
 * It checks the routing rather than fetching pages, because the built app is not part of a backend-only build.
 */
class FrontendRoutesTest extends PostgresIntegrationTest {

    private static final Pattern ROUTE = Pattern.compile("<Route path=\"([^\"*]+)\"");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyRouteOfTheWebAppIsHandedToTheApp() throws Exception {
        List<String> routes = routes();
        assertThat(routes).as("routes read from main.tsx").hasSizeGreaterThan(20).contains("/verify-email/sample", "/bank/3ds/sample");

        List<String> missing = new ArrayList<>();
        for (String route : routes) {
            HandlerExecutionChain chain = mappings.getHandler(new MockHttpServletRequest("GET", route));
            boolean served = chain != null && chain.getHandler() instanceof HandlerMethod method
                    && method.getBeanType().equals(FrontendController.class);
            if (!served) {
                missing.add(route);
            }
        }

        assertThat(missing).as("pages the server does not hand to the web app").isEmpty();
    }

    /** Nested routes in the router are written without their leading slash; the index route is "/". */
    private static List<String> routes() throws IOException {
        String router = Files.readString(Path.of("..", "frontend", "src", "main.tsx"));
        Matcher matcher = ROUTE.matcher(router);
        List<String> routes = new ArrayList<>();
        while (matcher.find()) {
            String path = matcher.group(1).replaceAll(":[a-zA-Z]+", "sample");
            routes.add(path.startsWith("/") ? path : "/" + path);
        }
        return routes;
    }
}
