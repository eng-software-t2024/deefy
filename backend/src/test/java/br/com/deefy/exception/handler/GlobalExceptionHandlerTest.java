package br.com.deefy.exception.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAuthenticationFailuresToUnauthorized() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/private");

        var response = handler.handleAuthenticationException(
                new AuthenticationServiceException("authentication provider unavailable"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getBody().path()).isEqualTo("/api/private");
    }

    @Test
    void keepsBadCredentialsSpecificMessage() {
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");

        var response = handler.handleBadCredentials(new BadCredentialsException("bad"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().messages()).containsExactly("E-mail ou senha inválidos.");
    }
}
