package com.example.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.LogManager;

/**
 * Initialise java.util.logging a partir de logging.properties (classpath).
 * Hibernate (jboss-logging) est force sur le backend JUL pour avoir des logs homogenes.
 */
public final class LoggingConfig {

    private static boolean initialized;

    private LoggingConfig() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        if (System.getProperty("org.jboss.logging.provider") == null) {
            System.setProperty("org.jboss.logging.provider", "jdk");
        }
        try {
            Files.createDirectories(Paths.get("logs"));
        } catch (IOException e) {
            System.err.println("Impossible de creer le dossier logs/ : " + e.getMessage());
        }
        try (InputStream is = LoggingConfig.class.getClassLoader().getResourceAsStream("logging.properties")) {
            if (is != null) {
                LogManager.getLogManager().readConfiguration(is);
            }
        } catch (IOException e) {
            System.err.println("Configuration des logs ignoree : " + e.getMessage());
        }
        initialized = true;
    }
}
