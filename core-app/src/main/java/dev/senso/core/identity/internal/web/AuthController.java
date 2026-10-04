package dev.senso.core.identity.internal.web;

import dev.senso.core.identity.internal.domain.User;
import dev.senso.core.identity.internal.service.AuthenticationService;
import dev.senso.core.identity.internal.service.LoginResult;
import dev.senso.core.identity.internal.service.RegistrationService;
import dev.senso.openapi.api.AuthApi;
import dev.senso.openapi.model.LoginRequest;
import dev.senso.openapi.model.LoginResponse;
import dev.senso.openapi.model.RegisterRequest;
import dev.senso.openapi.model.UserDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;

import java.time.ZoneOffset;

@Controller
public class AuthController implements AuthApi {

    private final RegistrationService registrationService;
    private final AuthenticationService authenticationService;

    public AuthController(
        RegistrationService registrationService,
        AuthenticationService authenticationService
    ) {
        this.registrationService = registrationService;
        this.authenticationService = authenticationService;
    }

    @Override
    public ResponseEntity<UserDto> register(RegisterRequest request) {

        User user = registrationService.register(
            request.getEmail(),
            request.getDisplayName(),
            request.getPassword()
        );

        UserDto response = new UserDto()
            .id(user.getId())
            .email(user.getEmail())
            .displayName(user.getDisplayName())
            .roles(
                user.getRoles()
                    .stream()
                    .map(role ->
                        UserDto.RolesEnum.fromValue(role.name())
                    )
                    .toList()
            )
            .createdAt(
                user.getCreatedAt()
                    .atOffset(ZoneOffset.UTC)
            );

        return ResponseEntity
            .status(201)
            .body(response);
    }

    @Override
    public ResponseEntity<LoginResponse> login(LoginRequest request) {

        LoginResult result = authenticationService.login(
            request.getEmail(),
            request.getPassword(),
            null
        );

        ResponseCookie refreshCookie = ResponseCookie
            .from("refresh_token", result.refreshToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .build();

        LoginResponse response = new LoginResponse()
            .accessToken(result.accessToken())
            .tokenType(LoginResponse.TokenTypeEnum.BEARER)
            .expiresInSeconds(LoginResponse.ExpiresInSecondsEnum.NUMBER_900);

        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshCookie.toString()
            )
            .body(response);
    }
}
