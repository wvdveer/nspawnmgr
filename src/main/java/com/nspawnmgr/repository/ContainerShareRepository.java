package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerShare;
import com.nspawnmgr.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContainerShareRepository extends JpaRepository<ContainerShare, Long> {

    /** Eagerly fetches user: rendered as s.user.username in detail.html, outside any open session. */
    @Query("select s from ContainerShare s left join fetch s.user where s.container = :container")
    List<ContainerShare> findByContainer(@Param("container") Container container);

    /** Eagerly fetches container: used to re-grant permissions when renaming a user's Guacamole account. */
    @Query("select s from ContainerShare s left join fetch s.container where s.user = :user")
    List<ContainerShare> findByUser(@Param("user") User user);

    Optional<ContainerShare> findByContainerAndUser(Container container, User user);

    boolean existsByContainerAndUser(Container container, User user);

    void deleteByContainerAndUser(Container container, User user);

    void deleteByContainer(Container container);
}
