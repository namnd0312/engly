package com.engly.identity.domain;

/**
 * Thrown after a successful password match on an account that has not clicked its activation
 * link yet → mapped to HTTP 403. Never thrown for unknown email / bad password (those stay 401
 * generic, see {@link InvalidLoginException}), so this never becomes an enumeration oracle.
 */
public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email chưa được kích hoạt");
    }
}
