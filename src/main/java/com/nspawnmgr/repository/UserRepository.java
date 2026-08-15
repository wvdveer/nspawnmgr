package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByExternalUserId(String externalUserId);

    Optional<User> findByUsernameIgnoreCase(String username);

    List<User> findByUsernameContainingIgnoreCase(String usernameFragment);

    long countByRole(Role role);

    /**
     * Row-locking read used by ShareService.ensureGuacamoleUser to serialize concurrent Guacamole
     * account provisioning for the same user - confirmed live, two containers created together for
     * the same owner both raced past a plain null-check on guacamoleUsername and both tried to
     * insert a guacamole_user_secrets row, the loser failing on the primary-key constraint. A
     * second transaction requesting this same row blocks at the database level until the first
     * commits (or rolls back), so it then reads the already-committed guacamoleUsername instead of
     * racing to create a second one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
