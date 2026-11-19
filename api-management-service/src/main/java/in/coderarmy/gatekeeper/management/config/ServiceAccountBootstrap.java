package in.coderarmy.gatekeeper.management.config;

import in.coderarmy.gatekeeper.management.entity.ManagementUser;
import in.coderarmy.gatekeeper.management.repository.ManagementUserRepository;
import in.coderarmy.gatekeeper.management.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
public class ServiceAccountBootstrap implements ApplicationRunner {
    private final ManagementUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String gatewayPassword;

    public ServiceAccountBootstrap(ManagementUserRepository users, RoleRepository roles,
                                   PasswordEncoder encoder,
                                   @Value("${GATEKEEPER_ADMIN_PASSWORD:platform12345678}") String adminPassword,
                                   @Value("${GATEKEEPER_GATEWAY_PASSWORD:gateway12345678}") String gatewayPassword) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.gatewayPassword = gatewayPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createRoleIfMissing("PLATFORM_ADMIN");
        createRoleIfMissing("TENANT_ADMIN");
        createRoleIfMissing("GATEWAY_SERVICE");
        createIfMissing("platform-admin", "PLATFORM_ADMIN", adminPassword);
        createIfMissing("gateway-service", "GATEWAY_SERVICE", gatewayPassword);
    }

    private void createRoleIfMissing(String name) {
        if (roles.findByName(name).isEmpty()) {
            in.coderarmy.gatekeeper.management.entity.Role role = new in.coderarmy.gatekeeper.management.entity.Role();
            role.setName(name);
            roles.save(role);
        }
    }

    private void createIfMissing(String username, String role, String password) {
        if (password == null || password.isBlank() || password.length() < 8) {
            password = "platform-secure-password-" + username;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            password = password.substring(0, 72);
        }
        if (users.existsByUsername(username)) {
            return;
        }
        ManagementUser user = new ManagementUser();
        user.setUsername(username);
        user.setRole(roles.findByName(role).orElseThrow());
        user.setPasswordHash(encoder.encode(password));
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        users.save(user);
    }
}
