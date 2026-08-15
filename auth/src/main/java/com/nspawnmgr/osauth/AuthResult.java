package com.nspawnmgr.osauth;

/** Distinguishes "wrong credentials" from "valid credentials, not authorized" so LoginServlet can describe the unmet requirement in the latter case. */
public sealed interface AuthResult {

    record Success(String fullName) implements AuthResult {
    }

    record InvalidCredentials() implements AuthResult {
    }

    /** requirement is a human-readable description, e.g. "membership in the 'foo' group" or "access to the 'bar' share". */
    record NotAuthorized(String requirement) implements AuthResult {
    }
}
