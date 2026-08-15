package com.nspawnmgr.service;

import com.nspawnmgr.domain.GuacamoleUserSecret;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.GuacamoleUserSecretRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates {@link GuacamoleUserSecret}'s own insert-or-update into its own transactions, separate
 * from {@link ShareService#resetGuacamoleAccount}'s own caller-level transaction - confirmed live
 * (arch-kde, 2026-08-14): a plain {@code findById}-then-branch there occasionally still found no
 * row even though one genuinely existed (this table has no analogous {@code userRepository
 * .findByIdForUpdate}-style row lock protecting it directly - only the owning {@code User} row is
 * locked), so the "not present" branch's {@code save(new GuacamoleUserSecret(...))} hit the
 * primary-key constraint. Catching that within the same {@code @Transactional} method doesn't
 * help - per the JPA spec, a failed flush marks the current transaction rollback-only regardless
 * of whether the caller catches the translated exception, so any further write in that same
 * transaction (including a "fall back to update instead" retry) would silently never commit. Each
 * method here gets its own fresh transaction instead, so a failure in {@link #insert} doesn't
 * poison {@link #update}'s own attempt, or the caller's.
 *
 * <p>A separate {@code @Service} rather than private methods on {@code ShareService} because
 * {@code REQUIRES_NEW} only takes effect through Spring's own proxy - a same-class call
 * ({@code this.insert(...)}) bypasses that proxy entirely and the annotation would be silently
 * ignored.
 */
@Service
public class GuacamoleUserSecretWriter {

    private final GuacamoleUserSecretRepository repository;

    GuacamoleUserSecretWriter(GuacamoleUserSecretRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(User user, String ciphertext, String iv) {
        repository.saveAndFlush(new GuacamoleUserSecret(user, ciphertext, iv));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(Long userId, String ciphertext, String iv) {
        repository.findById(userId).ifPresent(secret -> {
            secret.setPasswordCiphertext(ciphertext);
            secret.setIv(iv);
        });
    }
}
