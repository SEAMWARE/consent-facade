package org.fiware.consent;

import io.micronaut.runtime.Micronaut;

/**
 * Entrypoint of the Consent Facade.
 */
public class Application {

    /**
     * Starts the facade.
     *
     * @param args the command-line arguments, passed on to Micronaut
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
