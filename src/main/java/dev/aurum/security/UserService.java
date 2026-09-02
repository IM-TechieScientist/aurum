package dev.aurum.security;

import dev.aurum.audit.AuditAction;
import dev.aurum.audit.AuditService;
import dev.aurum.common.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    private static final Set<UUID> BOOTSTRAP_IDS = Set.of(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            UUID.fromString("00000000-0000-0000-0000-000000000103"),
            UUID.fromString("00000000-0000-0000-0000-000000000104"));

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();

    public UserService(AppUserRepository users, PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Transactional
    public UserView create(String username, String password, UserRole role) {
        String normalizedUsername = username.strip().toLowerCase(Locale.ROOT);
        AppUserView user;
        try {
            user = users.create(UUID.randomUUID(), normalizedUsername,
                    passwords.encode(password), role, Instant.now(clock));
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS",
                    "The username is already in use");
        }
        audit.record(AuditAction.CREATE_USER, "USER", user.id(), null);
        return UserView.from(user);
    }

    @Transactional
    public UserView changeRole(UUID userId, UserRole role) {
        AppUserView current = required(userId);
        if (BOOTSTRAP_IDS.contains(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_USER_IMMUTABLE",
                    "Configured bootstrap identities keep their assigned roles");
        }
        if (current.role() == UserRole.CUSTOMER && role != UserRole.CUSTOMER
                && users.ownedAccountCount(userId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_OWNS_ACCOUNTS",
                    "Transfer account ownership before changing this customer's role");
        }
        users.updateRole(userId, role);
        AppUserView updated = required(userId);
        if (updated.role() != current.role()) {
            audit.record(AuditAction.CHANGE_USER_ROLE, "USER", userId,
                    current.role().name() + "->" + updated.role().name());
        }
        return UserView.from(updated);
    }

    @Transactional(readOnly = true)
    public List<UserView> all() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    private AppUserView required(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found"));
    }
}
