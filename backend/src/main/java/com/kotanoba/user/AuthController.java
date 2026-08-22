package com.kotanoba.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            // 409, not the generic-message pattern used on login: registration
            // is inherently confirming account existence to its own owner
            // (you just tried to create it), so there's no enumeration risk
            // to defend against here the way there is on login.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        AppUser user = userRepository.save(
            new AppUser(request.email(), passwordEncoder.encode(request.password()))
        );
        return issueTokens(user.getId());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        AppUser user = userRepository.findByEmail(request.email())
            .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
            // Same message whether the email doesn't exist or the password is
            // wrong — telling them apart would let a caller enumerate which
            // emails have accounts.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid email or password"));
        return issueTokens(user.getId());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshTokenService.RotatedTokens rotated = refreshTokenService.rotate(request.refreshToken())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid or expired refresh token"));
        return AuthResponse.of(
            jwtService.issueAccessToken(rotated.userId()),
            rotated.newRawToken(),
            jwtService.accessTokenTtlSeconds()
        );
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }

    private AuthResponse issueTokens(long userId) {
        return AuthResponse.of(
            jwtService.issueAccessToken(userId),
            refreshTokenService.issue(userId),
            jwtService.accessTokenTtlSeconds()
        );
    }
}
