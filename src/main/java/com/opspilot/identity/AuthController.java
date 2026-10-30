package com.opspilot.identity;

import com.opspilot.common.ForbiddenException;
import com.opspilot.identity.AuthService.SignIn;
import com.opspilot.identity.SessionService.Refreshed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in returns a short-lived access token and sets the session cookie, which {@code /refresh}
 * exchanges for new access tokens until the session ends (see ADR 0006).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
    }

    /** Required on requests that use the session cookie; any value. */
    static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService auth;
    private final SessionService sessions;
    private final TokenService tokens;
    private final SessionCookie cookie;

    AuthController(AuthService auth, SessionService sessions, TokenService tokens, SessionCookie cookie) {
        this.auth = auth;
        this.sessions = sessions;
        this.tokens = tokens;
        this.cookie = cookie;
    }

    /** Ends the browser's previous session, if it had one: one browser, one session. */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
            @CookieValue(name = SessionCookie.NAME, required = false) String previousSession) {
        SignIn signIn = auth.login(request.email(), request.password());
        sessions.end(previousSession);
        return signedIn(HttpStatus.OK, signIn);
    }

    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        return signedIn(HttpStatus.CREATED,
                auth.signup(request.companyName(), request.name(), request.email(), request.password()));
    }

    /**
     * A new access token for the session cookie, which is rotated: the token it held cannot be used
     * again. 401, and the cookie deleted, once the session has ended.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = SessionCookie.NAME, required = false) String token, HttpServletRequest request) {
        requireSameOrigin(request);
        Refreshed refreshed = sessions.refresh(token);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (refreshed.rotated() != null) {
            response.header(HttpHeaders.SET_COOKIE, cookie.set(refreshed.rotated()));
        }
        return response.body(tokens.issue(refreshed.user()));
    }

    /**
     * Ends the session on the server, so the cookie cannot bring it back, and deletes the cookie.
     * Access tokens already issued stay valid until they expire, at most {@code opspilot.security.jwt.ttl}.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = SessionCookie.NAME, required = false) String token, HttpServletRequest request) {
        requireSameOrigin(request);
        sessions.end(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @ExceptionHandler(InvalidSessionException.class)
    ResponseEntity<ProblemDetail> sessionEnded(InvalidSessionException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cookie.clear())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    private ResponseEntity<TokenResponse> signedIn(HttpStatus status, SignIn signIn) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.set(signIn.session()))
                .body(signIn.accessToken());
    }

    /**
     * The browser attaches the session cookie by itself, so endpoints acting on it must make sure
     * OpsPilot's own pages sent the request (CSRF). Another site cannot add a custom header without a
     * CORS preflight, which OpsPilot never grants; and browsers say where a request came from in
     * Sec-Fetch-Site. SameSite=Strict on the cookie is a third, independent barrier.
     */
    private static void requireSameOrigin(HttpServletRequest request) {
        if (request.getHeader(CSRF_HEADER) == null) {
            throw new ForbiddenException("Requests that use the session cookie must send the " + CSRF_HEADER
                    + " header");
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !"same-origin".equals(site)) {
            throw new ForbiddenException("The session cookie cannot be used from another site");
        }
    }
}
