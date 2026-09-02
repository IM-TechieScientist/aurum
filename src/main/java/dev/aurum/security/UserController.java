package dev.aurum.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserView create(@Valid @RequestBody CreateUserRequest request) {
        return users.create(request.username(), request.password(), request.role());
    }

    @GetMapping
    List<UserView> all() {
        return users.all();
    }

    @PatchMapping("/{userId}/role")
    UserView changeRole(@PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest request) {
        return users.changeRole(userId, request.role());
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 80)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotNull UserRole role
    ) {
    }

    public record ChangeRoleRequest(@NotNull UserRole role) {
    }
}
