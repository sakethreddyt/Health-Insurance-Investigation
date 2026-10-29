package com.healthclaim.casedesk.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void missingAadhaarInBrowserReturnsHtml404Page() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/users/inv123/aadhar-file");
        request.addHeader("Accept", MediaType.TEXT_HTML_VALUE);

        var response = handler.status(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
        String body = assertInstanceOf(String.class, response.getBody());
        assertTrue(body.contains("Aadhaar file not found"));
        assertTrue(body.contains("ERROR 404"));
    }

    @Test
    void missingAadhaarInApiRequestRemainsJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/users/inv123/aadhar-file");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);

        var response = handler.status(new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertInstanceOf(Map.class, response.getBody());
        assertEquals("File not found", ((Map<?, ?>) response.getBody()).get("message"));
    }
}
