package com.example.test;

import com.example.model.Reservation;
import com.example.util.DataInitializer;
import com.example.util.PerformanceReport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DataInitializerEtRapportTest {

    private static final String BASE = "data_test";
    private static EntityManagerFactory emf;

    @BeforeAll
    public static void init() throws Exception {
        emf = TestSupport.creerBase(BASE, true);
    }

    @AfterAll
    public static void close() {
        TestSupport.detruireBase(emf, BASE);
    }

    @Test
    public void jeuDeDonneesCoherent() {
        EntityManager em = emf.createEntityManager();
        try {
            assertEquals(12L, compter(em, "Utilisateur"));
            assertEquals(8L, compter(em, "Salle"));
            assertEquals(6L, compter(em, "Equipement"));
            assertTrue(compter(em, "Reservation") > 100);

            // aucune reservation active ne chevauche une autre dans la meme salle
            Long chevauchements = em.createQuery(
                    "SELECT COUNT(r1) FROM Reservation r1, Reservation r2 WHERE r1.salle = r2.salle AND r1.id < r2.id "
                            + "AND r1.statut = 'CONFIRMEE' AND r2.statut = 'CONFIRMEE' "
                            + "AND r1.dateDebut < r2.dateFin AND r1.dateFin > r2.dateDebut", Long.class).getSingleResult();
            assertEquals(0L, chevauchements);

            // capacite toujours respectee
            List<Reservation> trop = em.createQuery(
                    "SELECT r FROM Reservation r WHERE r.nombreParticipants > r.salle.capacite", Reservation.class).getResultList();
            assertTrue(trop.isEmpty());
        } finally {
            em.close();
        }
    }

    @Test
    public void initialisationIdempotente() {
        EntityManager em = emf.createEntityManager();
        try {
            assertFalse(new DataInitializer(em).initData(), "deuxieme appel : rien n'est insere");
            assertEquals(12L, compter(em, "Utilisateur"));
        } finally {
            em.close();
        }
    }

    @Test
    public void rapportDePerformance(@TempDir Path dossier) throws Exception {
        PerformanceReport report = new PerformanceReport(emf);
        String texte = report.generateReport();
        assertTrue(texte.contains("RAPPORT DE PERFORMANCE"));
        assertTrue(texte.contains("Cache de second niveau hits"));
        assertTrue(texte.contains("Requetes executees"));
        assertTrue(texte.contains("com.example.model.Salle"));

        Path fichier = dossier.resolve("rapports/perf.txt");
        report.exportReport(fichier);
        assertTrue(new String(Files.readAllBytes(fichier), StandardCharsets.UTF_8).contains("Recommandations"));
    }

    private static long compter(EntityManager em, String entite) {
        return em.createQuery("SELECT COUNT(e) FROM " + entite + " e", Long.class).getSingleResult();
    }
}
