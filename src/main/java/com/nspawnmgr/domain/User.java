package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * {@code @DynamicUpdate}: UserService#upsert re-saves this entity on every authenticated request
 * (syncing username/email/fullName/role from the auth cookie), and without this annotation
 * Hibernate's default full-column UPDATE would also rewrite every OTHER field using whatever
 * value happened to be in memory when that request's copy was loaded - confirmed live, a
 * concurrent upsert() call that loaded this row before ShareService.ensureGuacamoleUser's own
 * commit clobbered guacamoleUsername back to null moments later, a lost-update race. With this
 * annotation, Hibernate only writes columns actually mutated on a given entity instance, so a
 * method that never touches guacamoleUsername can no longer stomp it (or any other field it
 * doesn't own) regardless of how stale its own snapshot of the row was.
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_user_id", nullable = false, unique = true)
    private String externalUserId;

    private String username;

    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "guacamole_username")
    private String guacamoleUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String externalUserId) {
        this.externalUserId = externalUserId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
