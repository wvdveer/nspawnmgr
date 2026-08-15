package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.MapsId;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/** The generated password nspawnmgr uses to log in as a user's provisioned Guacamole account for SSO. */
@Entity
@Table(name = "guacamole_user_secrets")
@Getter
@Setter
@NoArgsConstructor
public class GuacamoleUserSecret implements Persistable<Long> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @javax.persistence.JoinColumn(name = "user_id")
    private User user;

    @Column(name = "password_ciphertext", nullable = false, length = 2000)
    private String passwordCiphertext;

    @Column(nullable = false, length = 64)
    private String iv;

    public GuacamoleUserSecret(User user, String passwordCiphertext, String iv) {
        this.user = user;
        this.userId = user.getId();
        this.passwordCiphertext = passwordCiphertext;
        this.iv = iv;
    }

    @Override
    public Long getId() {
        return userId;
    }

    /**
     * Always true: this @MapsId shared-PK entity's userId is set in the constructor (mirroring
     * the owning User's already-persisted id), so Spring Data's default null-id "is this new"
     * check is always fooled into thinking it already exists, calling entityManager.merge() on a
     * row that's never actually been saved yet — which fails ("null identifier") because
     * Hibernate's merge cascade can't resolve the @OneToOne/@MapsId user association on an entity
     * that was never really persisted. ShareService.ensureGuacamoleUser only ever constructs and
     * saves this once per user, never re-saves an existing one, so always-new is correct here.
     */
    @Override
    public boolean isNew() {
        return true;
    }
}
