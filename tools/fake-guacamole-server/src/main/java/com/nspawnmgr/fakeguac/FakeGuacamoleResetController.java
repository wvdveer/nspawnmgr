package com.nspawnmgr.fakeguac;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-control-only endpoint, not part of the mimicked Guacamole API, so it stays unprefixed (see FakeGuacamoleController). */
@RestController
public class FakeGuacamoleResetController {

    private final FakeGuacamoleStore store;

    public FakeGuacamoleResetController(FakeGuacamoleStore store) {
        this.store = store;
    }

    @PostMapping("/fake/reset")
    public ResponseEntity<Void> reset() {
        store.reset();
        return ResponseEntity.noContent().build();
    }
}
