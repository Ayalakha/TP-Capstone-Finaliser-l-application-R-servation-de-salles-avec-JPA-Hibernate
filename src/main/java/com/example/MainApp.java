package com.example;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.repository.ReservationFiltre;
import com.example.repository.ReservationRepository;
import com.example.repository.SalleRepository;
import com.example.service.ConcurrenceException;
import com.example.service.ReservationException;
import com.example.service.ReservationService;
import com.example.util.DataInitializer;
import com.example.util.JpaUtil;
import com.example.util.LoggingConfig;
import com.example.util.MigrationExecutor;
import com.example.util.PerformanceReport;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application complete : migration -> donnees -> demonstrations (cache, requetes, transactions) -> rapport.
 * <p>
 * Lancement : {@code mvn compile exec:java}
 * (MySQL : {@code mvn compile exec:java -Ddb.url=jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true -Ddb.user=root -Ddb.password=password})
 */
public class MainApp {

    private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

    public static void main(String[] args) {
        LoggingConfig.init();
        int exitCode = 0;
        EntityManagerFactory emf = null;
        try {
            // 1. Migration du schema (avant Hibernate, qui valide le mapping au demarrage)
            titre("1. Migration de la base");
            MigrationExecutor migrationExecutor = new MigrationExecutor(
                    JpaUtil.getJdbcUrl(), JpaUtil.getUser(), JpaUtil.getPassword());
            migrationExecutor.migrate(MigrationExecutor.DEFAULT_SCRIPTS);
            System.out.println("Versions appliquees : " + migrationExecutor.appliedVersions());

            emf = JpaUtil.createEntityManagerFactory();

            // 2. Jeu de donnees
            titre("2. Initialisation des donnees");
            EntityManager em = emf.createEntityManager();
            try {
                new DataInitializer(em).initData();
            } finally {
                em.close();
            }

            // 3. Demonstrations
            demoCache(emf);
            demoRequetes(emf);
            demoService(emf);

            // 4. Rapport
            titre("6. Rapport de performance");
            PerformanceReport report = new PerformanceReport(emf);
            report.printReport();
            report.exportReport(Paths.get("target", "performance-report.txt"));
        } catch (MigrationExecutor.MigrationException e) {
            LOGGER.log(Level.SEVERE, "Migration impossible, arret de l'application", e);
            exitCode = 2;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Arret de l'application suite a une erreur inattendue", e);
            exitCode = 1;
        } finally {
            if (emf != null && emf.isOpen()) {
                emf.close();
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Etape 2 : meme salle lue depuis deux EntityManager differents -> le 2e acces vient du cache L2. */
    private static void demoCache(EntityManagerFactory emf) {
        titre("3. Cache de second niveau");
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        emf.getCache().evictAll();

        Long salleId = premiereSalleId(emf);
        for (int i = 1; i <= 2; i++) {
            long sqlAvant = stats.getPrepareStatementCount();
            long hitsAvant = stats.getSecondLevelCacheHitCount();
            EntityManager em = emf.createEntityManager();
            try {
                Salle salle = em.find(Salle.class, salleId);
                System.out.printf("Lecture %d de '%s' : %d requete(s) SQL, %d hit(s) cache L2%n", i, salle.getNom(),
                        stats.getPrepareStatementCount() - sqlAvant, stats.getSecondLevelCacheHitCount() - hitsAvant);
            } finally {
                em.close();
            }
        }
        System.out.println("Salle presente dans le cache : " + emf.getCache().contains(Salle.class, salleId));

        for (int i = 1; i <= 2; i++) {
            long qHitsAvant = stats.getQueryCacheHitCount();
            EntityManager em = emf.createEntityManager();
            try {
                int nb = new SalleRepository(em).findAll().size();
                System.out.printf("Liste des salles (%d) - appel %d : %d hit(s) cache de requetes%n",
                        nb, i, stats.getQueryCacheHitCount() - qHitsAvant);
            } finally {
                em.close();
            }
        }
    }

    /** Etape 9 : JPQL vs Criteria API. */
    private static void demoRequetes(EntityManagerFactory emf) {
        titre("4. Requetes JPQL et Criteria API");
        EntityManager em = emf.createEntityManager();
        try {
            ReservationRepository reservations = new ReservationRepository(em);
            SalleRepository salles = new SalleRepository(em);
            LocalDateTime maintenant = LocalDateTime.now();

            List<Reservation> jpql = reservations.findBySalleNomApres("Salle A", maintenant);
            List<Reservation> criteria = reservations.findBySalleNomApresCriteria("Salle A", maintenant);
            System.out.println("Reservations a venir de la Salle A : JPQL=" + jpql.size() + ", Criteria=" + criteria.size());
            jpql.stream().limit(3).forEach(r -> System.out.printf("  %s -> %s  %-18s %s%n",
                    r.getDateDebut(), r.getDateFin().toLocalTime(), r.getUtilisateur().getNom(), r.getMotif()));

            List<Reservation> filtrees = reservations.rechercher(new ReservationFiltre()
                    .utilisateur("alice.dupont@example.com").apres(maintenant));
            System.out.println("Recherche dynamique (Alice, a venir) : " + filtrees.size() + " reservation(s)");

            LocalDateTime debut = prochainJourOuvre().atTime(14, 0);
            LocalDateTime fin = debut.plusHours(2);
            List<Salle> dispo = salles.findSallesDisponibles(debut, fin, 10);
            List<Salle> dispoCriteria = salles.findSallesDisponiblesCriteria(debut, fin, 10);
            System.out.println("Salles libres (>= 10 places) le " + debut + " : " + noms(dispo)
                    + (dispo.equals(dispoCriteria) ? "  [Criteria identique]" : "  [Criteria DIFFERENT !]"));

            System.out.println("Salles avec visioconference : " + noms(salles.findByEquipement("Visioconference")));
            System.out.println("Occupation (reservations actives) :");
            salles.statistiquesOccupation().forEach(s -> System.out.println("  " + s));
        } finally {
            em.close();
        }
    }

    /** Etapes 10 et 11 : transactions, regles metier, verrouillage optimiste, gestion des erreurs. */
    private static void demoService(EntityManagerFactory emf) {
        titre("5. Service de reservation (transactions, conflits, verrouillage optimiste)");
        ReservationService service = new ReservationService(emf);
        Long aliceId;
        Long salleId;
        EntityManager em = emf.createEntityManager();
        try {
            aliceId = em.createQuery("SELECT u FROM Utilisateur u WHERE u.email = :e", Utilisateur.class)
                    .setParameter("e", "alice.dupont@example.com").getSingleResult().getId();
            salleId = new SalleRepository(em).findByNom("Salle Lovelace").orElseThrow().getId();
        } finally {
            em.close();
        }

        // Creneau garanti libre : dans 30 jours a 7h (le jeu de donnees reserve entre 8h et 19h sur 3 semaines)
        LocalDateTime debut = LocalDate.now().plusDays(30).atTime(7, 0);
        Reservation r = service.creerReservation(aliceId, salleId, debut, debut.plusHours(1), 4, "Demo MainApp");
        System.out.println("Reservation creee : " + r);

        try {
            service.creerReservation(aliceId, salleId, debut.plusMinutes(30), debut.plusHours(2), 3, "Doublon");
        } catch (ReservationException e) {
            System.out.println("Refus attendu (chevauchement) : " + e.getMessage());
        }
        try {
            service.creerReservation(aliceId, salleId, debut.plusHours(3), debut.plusHours(4), 50, "Trop de monde");
        } catch (ReservationException e) {
            System.out.println("Refus attendu (capacite) : " + e.getMessage());
        }

        // Verrouillage optimiste : deux "utilisateurs" lisent la meme version puis enregistrent
        Reservation copieUtilisateur1 = service.trouverReservation(r.getId()).orElseThrow();
        Reservation copieUtilisateur2 = service.trouverReservation(r.getId()).orElseThrow();
        copieUtilisateur1.setMotif("Demo MainApp - modifie par l'utilisateur 1");
        service.mettreAJour(copieUtilisateur1);
        System.out.println("Utilisateur 1 : modification enregistree");
        copieUtilisateur2.setMotif("Demo MainApp - modifie par l'utilisateur 2");
        try {
            service.mettreAJour(copieUtilisateur2);
        } catch (ConcurrenceException e) {
            System.out.println("Utilisateur 2 : " + e.getMessage());
        }

        service.annulerReservation(r.getId());
        System.out.println("Statut final : " + service.trouverReservation(r.getId()).orElseThrow().getStatut());
    }

    private static Long premiereSalleId(EntityManagerFactory emf) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT MIN(s.id) FROM Salle s", Long.class).getSingleResult();
        } finally {
            em.close();
        }
    }

    private static LocalDate prochainJourOuvre() {
        LocalDate d = LocalDate.now().plusDays(1);
        while (d.getDayOfWeek().getValue() >= 6) {
            d = d.plusDays(1);
        }
        return d;
    }

    private static String noms(List<Salle> salles) {
        return salles.stream().map(Salle::getNom).reduce((a, b) -> a + ", " + b).orElse("(aucune)");
    }

    private static void titre(String titre) {
        System.out.println();
        System.out.println("==== " + titre + " ====");
    }
}
