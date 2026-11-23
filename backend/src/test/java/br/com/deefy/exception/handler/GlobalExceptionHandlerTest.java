package br.com.deefy.exception.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAuthenticationProviderOutageToServiceUnavailable() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/private");

        var response = handler.handleAuthenticationServiceException(
                new AuthenticationServiceException("authentication provider unavailable"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getBody().path()).isEqualTo("/api/private");
    }

    @Test
    void mapsOtherAuthenticationFailuresToUnauthorized() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/private");

        var response = handler.handleAuthenticationException(
                new InsufficientAuthenticationException("authentication required"),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void keepsBadCredentialsSpecificMessage() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");

        var response = handler.handleBadCredentials(new BadCredentialsException("bad"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().messages()).containsExactly("E-mail ou senha inválidos.");
    }
}
