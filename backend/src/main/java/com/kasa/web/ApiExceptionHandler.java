package com.kasa.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders validation failures and {@code ResponseStatusException} as RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final Resource frontendIndex;

    public ApiExceptionHandler(@Value("${kasa.frontend-index:classpath:static/index.html}") Resource frontendIndex) {
        this.frontendIndex = frontendIndex;
    }

    /**
     * A person who follows a wrong link to a page gets the web app, which explains what went wrong, still with a
     * 404 so search engines and monitors see a missing page. Programs, the API and missing files keep the problem
     * detail.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        if (wantsPage(request) && frontendIndex.isReadable()) {
            try (InputStream in = frontendIndex.getInputStream()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body(in.readAllBytes());
            } catch (IOException e) {
                logger.warn("Could not read the web app's entry page", e);
            }
        }
        return super.handleNoResourceFoundException(ex, headers, status, request);
    }

    private static boolean wantsPage(WebRequest request) {
        if (!(request instanceof ServletWebRequest web) || !"GET".equals(web.getRequest().getMethod())) {
            return false;
        }
        String path = web.getRequest().getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/actuator") || path.substring(path.lastIndexOf('/') + 1).contains(".")) {
            return false;
        }
        String accept = web.getHeader(HttpHeaders.ACCEPT);
        if (accept == null) {
            return false;
        }
        List<MediaType> accepted = MediaType.parseMediaTypes(accept);
        return accepted.stream().anyMatch(type -> type.isCompatibleWith(MediaType.TEXT_HTML) && !type.isWildcardType());
    }
}
