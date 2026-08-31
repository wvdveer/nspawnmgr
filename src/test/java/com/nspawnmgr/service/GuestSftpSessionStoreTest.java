package com.nspawnmgr.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class GuestSftpSessionStoreTest {

    private final GuestSftpSessionStore store = new GuestSftpSessionStore();

    @Test
    void getReturnsNullWhenNothingWasEverStored() {
        assertThat(store.get(new MockHttpSession(), 1L)).isNull();
    }

    @Test
    void putThenGetRoundTrips() {
        MockHttpSession session = new MockHttpSession();
        store.put(session, 1L, "alice", "hunter2".toCharArray());

        GuestSftpSessionStore.Credential credential = store.get(session, 1L);

        assertThat(credential.username()).isEqualTo("alice");
        assertThat(credential.password()).isEqualTo("hunter2".toCharArray());
    }

    @Test
    void keyedByContainerIdSoSeparateTabsDontCollide() {
        MockHttpSession session = new MockHttpSession();
        store.put(session, 1L, "alice", "pw1".toCharArray());
        store.put(session, 2L, "bob", "pw2".toCharArray());

        assertThat(store.get(session, 1L).username()).isEqualTo("alice");
        assertThat(store.get(session, 2L).username()).isEqualTo("bob");
    }

    @Test
    void clearRemovesOnlyThatContainersCredential() {
        MockHttpSession session = new MockHttpSession();
        store.put(session, 1L, "alice", "pw1".toCharArray());
        store.put(session, 2L, "bob", "pw2".toCharArray());

        store.clear(session, 1L);

        assertThat(store.get(session, 1L)).isNull();
        assertThat(store.get(session, 2L)).isNotNull();
    }

    /** Regression test for a real bug found live: the connect endpoint zeroes its own password
     *  array in a finally block right after calling put() for best-effort hygiene - if put()
     *  stored that same array by reference instead of cloning it, this zeroing would silently wipe
     *  the stored credential too (a char[] is passed by reference), so every subsequent
     *  list/download/upload authenticated with a blank password and failed with "Exhausted
     *  available authentication methods" - indistinguishable from a genuinely wrong password. */
    @Test
    void callerZeroingItsOwnArrayAfterPutDoesNotCorruptTheStoredCredential() {
        MockHttpSession session = new MockHttpSession();
        char[] password = "hunter2".toCharArray();

        store.put(session, 1L, "alice", password);
        java.util.Arrays.fill(password, '\0');

        assertThat(store.get(session, 1L).password()).isEqualTo("hunter2".toCharArray());
    }

    @Test
    void clearZeroesTheStoredPasswordBeforeDiscardingIt() {
        MockHttpSession session = new MockHttpSession();
        store.put(session, 1L, "alice", "hunter2".toCharArray());
        GuestSftpSessionStore.Credential credential = store.get(session, 1L);

        store.clear(session, 1L);

        assertThat(credential.password()).isEqualTo(new char[]{0, 0, 0, 0, 0, 0, 0});
    }
}
