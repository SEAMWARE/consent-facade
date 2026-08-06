package org.fiware.consent;

import io.micronaut.runtime.Micronaut;

/**
 * Entrypoint of the Consent Facade.
 */
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
