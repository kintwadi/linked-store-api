package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.entity.UserAccount;
import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.entity.UserStatus;
import com.vicinity24.core.linkedstore.api.repository.UserAccountRepository;
import com.vicinity24.core.linkedstore.api.service.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RootAdminSeeder {

    private static final String ENV_LINKEDSTORE_PLATFORM_ROOT_ADMIN = "LINKEDSTORE_PLATFORM_ROOT_ADMIN";

    private final UserAccountRepository userAccountRepository;
    private final PasswordService passwordService;
    private final AuthProperties authProperties;
    private final Environment environment;

    @PersistenceContext
    private EntityManager em;

    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void seedRootAdmin() {
        if (!isRootAdminEnabled()) {
            log.info("RootAdminSeeder: {}=false -> skipping root admin seeding", ENV_LINKEDSTORE_PLATFORM_ROOT_ADMIN);
            return;
        }
        ensureStoreUsersColumnsExist();
        seedGlobalAdminUser();
    }

    private boolean isRootAdminEnabled() {
        String raw = environment.getProperty(ENV_LINKEDSTORE_PLATFORM_ROOT_ADMIN);
        if (raw == null || raw.isBlank()) {
            String sys = System.getenv(ENV_LINKEDSTORE_PLATFORM_ROOT_ADMIN);
            if (sys != null && !sys.isBlank()) raw = sys;
        }
        if (raw == null || raw.isBlank()) return true;
        return Boolean.parseBoolean(raw.trim());
    }

    private void ensureStoreUsersColumnsExist() {
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS email VARCHAR(255)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS password_salt VARCHAR(128)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS is_global_admin BOOLEAN NOT NULL DEFAULT FALSE");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE'");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS pin_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS api_key_hash VARCHAR(512)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS phone_number VARCHAR(64)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS name VARCHAR(255)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS role VARCHAR(50)");
        runDdlSilently("ALTER TABLE store_users ADD COLUMN IF NOT EXISTS store_id UUID");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN role TYPE VARCHAR(50)");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN name TYPE VARCHAR(255)");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN store_id DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN pin_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN api_key_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN status DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN email DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN password_salt DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN password_hash DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN name DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN role DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN is_global_admin DROP NOT NULL");
        runDdlSilently("ALTER TABLE store_users ALTER COLUMN phone_number DROP NOT NULL");
        runDdlSilently("CREATE UNIQUE INDEX IF NOT EXISTS idx_store_users_email ON store_users(email) WHERE email IS NOT NULL");
        runDdlSilently("""
            DO $$
            DECLARE
                fk_name text := 'fkj5dbp5e9tqy3y60f14ej32dap';
            BEGIN
                IF EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE constraint_name = fk_name AND table_name = 'store_users'
                ) THEN
                    EXECUTE format('ALTER TABLE store_users DROP CONSTRAINT %I', fk_name);
                END IF;
            END $$;
            """);
        runDdlSilently("""
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE constraint_name = 'fk_store_users_store' AND table_name = 'store_users'
                ) THEN
                    ALTER TABLE store_users
                    ADD CONSTRAINT fk_store_users_store
                    FOREIGN KEY (store_id) REFERENCES stores(id) ON DELETE CASCADE;
                END IF;
            END $$;
            """);
    }

    private void seedGlobalAdminUser() {
        try {
            List<UserAccount> existing = userAccountRepository.findGlobalAdmins();
            if (existing != null && !existing.isEmpty()) {
                log.info("RootAdminSeeder: global admin users already exist: {}. Skip seeding.", existing.size());
                return;
            }
        } catch (Exception e) {
            log.warn("RootAdminSeeder: cannot query global admins table (likely not yet created): {}", e.getMessage());
            return;
        }
        String email = authProperties.getDefaultAdminEmail() != null
                ? authProperties.getDefaultAdminEmail() : "admin@linked.store";
        String password = authProperties.getDefaultAdminPassword() != null
                ? authProperties.getDefaultAdminPassword() : "Admin123!";
        String salt = passwordService.generateSalt();
        String hash = passwordService.hash(password, salt);
        UserAccount admin = UserAccount.builder()
                .name("Global Admin")
                .email(email)
                .passwordSalt(salt)
                .passwordHash(hash)
                .role(UserRole.GLOBAL_ADMIN)
                .globalAdmin(true)
                .status(UserStatus.ACTIVE)
                .build();
        admin = userAccountRepository.save(admin);
        log.info("RootAdminSeeder: seeded global admin user: email={} id={}", email, admin.getId());
    }

    private void runDdlSilently(String sql) {
        try {
            Session session = em.unwrap(Session.class);
            session.doWork(connection -> {
                boolean autoCommitWas = connection.getAutoCommit();
                java.sql.Savepoint sp = null;
                try {
                    if (!autoCommitWas) {
                        sp = connection.setSavepoint("ddl_" + Long.toHexString(System.nanoTime() & 0xffffffffL));
                    }
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(sql);
                    }
                } catch (Exception e) {
                    if (sp != null) {
                        try { connection.rollback(sp); } catch (Exception ignore) {}
                    }
                    log.debug("RootAdminSeeder: DDL no-op (already exists or unsupported): {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("RootAdminSeeder: DDL no-op (EM-level): {}", e.getMessage());
        }
    }
}
