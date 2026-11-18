package com.example.test;

import com.example.util.DataInitializer;
import com.example.util.JpaUtil;
import com.example.util.LoggingConfig;
import com.example.util.MigrationExecutor;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Outils communs : chaque classe de test dispose de sa propre base H2 en memoire, migree et isolee. */
final class TestSupport {

    static {
        LoggingConfig.init();
    }

    private TestSupport() {
    }

    static String jdbcUrl(String nomBase) {
        return "jdbc:h2:mem:" + nomBase + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    /** Cree la base, applique les migrations, ouvre l'EntityManagerFactory et (optionnellement) charge les donnees. */
    static EntityManagerFactory creerBase(String nomBase, boolean avecDonnees) throws Exception {
        String url = jdbcUrl(nomBase);
        new MigrationExecutor(url, "sa", "").migrate(MigrationExecutor.DEFAULT_SCRIPTS);
        EntityManagerFactory emf = JpaUtil.createEntityManagerFactory(url, "sa", "");
        if (avecDonnees) {
            EntityManager em = emf.createEntityManager();
            try {
                new DataInitializer(em).initData();
            } finally {
                em.close();
            }
        }
        return emf;
    }

    /** Ferme l'EntityManagerFactory et detruit la base en memoire. */
    static void detruireBase(EntityManagerFactory emf, String nomBase) {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl(nomBase), "sa", "");
             Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // base deja fermee
        }
    }

    /**
     * Creneau lointain (au-dela des 3 semaines du jeu de donnees) pour que les tests
     * ne dependent pas des reservations generees.
     */
    static LocalDateTime creneau(int jourOffset, int heure) {
        return LocalDate.now().plusDays(60 + jourOffset).atTime(heure, 0);
    }
}
