package com.example.test;

import com.example.dto.StatistiqueSalle;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.repository.ReservationFiltre;
import com.example.repository.ReservationRepository;
import com.example.repository.SalleRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Requetes JPQL et Criteria API sur le jeu de donnees realiste. */
public class QueryTest {

    private static final String BASE = "query_test";

    private static EntityManagerFactory emf;
    private EntityManager em;
    private ReservationRepository reservations;
    private SalleRepository salles;

    @BeforeAll
    public static void init() throws Exception {
        emf = TestSupport.creerBase(BASE, true);
    }

    @AfterAll
    public static void close() {
        TestSupport.detruireBase(emf, BASE);
    }

    @BeforeEach
    public void setUp() {
        em = emf.createEntityManager();
        reservations = new ReservationRepository(em);
        salles = new SalleRepository(em);
    }

    @AfterEach
    public void tearDown() {
        em.close();
    }

    @Test
    public void jpqlEtCriteriaDonnentLeMemeResultat() {
        LocalDateTime maintenant = LocalDateTime.now();
        List<Reservation> jpql = reservations.findBySalleNomApres("Salle A", maintenant);
        List<Reservation> criteria = reservations.findBySalleNomApresCriteria("Salle A", maintenant);

        assertFalse(jpql.isEmpty(), "le jeu de donnees contient des reservations futures pour la Salle A");
        assertEquals(jpql, criteria);
        assertTrue(jpql.stream().allMatch(r -> r.getSalle().getNom().equals("Salle A") && r.getDateDebut().isAfter(maintenant)));
        for (int i = 1; i < jpql.size(); i++) {
            assertFalse(jpql.get(i).getDateDebut().isBefore(jpql.get(i - 1).getDateDebut()), "tri par date de debut");
        }
    }

    @Test
    public void joinFetchEviteLeProblemeNPlusUn() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        emf.getCache().evictAll();
        stats.clear();

        List<Reservation> liste = reservations.findBySalleNomApres("Salle A", LocalDateTime.now().minusDays(30));
        liste.forEach(r -> r.getUtilisateur().getNom()); // acces aux associations

        assertTrue(liste.size() > 5);
        assertEquals(1, stats.getPrepareStatementCount(), "une seule requete SQL grace a JOIN FETCH");
    }

    @Test
    public void rechercheDynamiqueCriteria() {
        List<Reservation> toutes = reservations.rechercher(new ReservationFiltre());
        List<Reservation> annulees = reservations.rechercher(new ReservationFiltre().statut(StatutReservation.ANNULEE));
        List<Reservation> alice = reservations.rechercher(new ReservationFiltre()
                .utilisateur("ALICE.DUPONT@example.com").statut(StatutReservation.CONFIRMEE));
        List<Reservation> grandes = reservations.rechercher(new ReservationFiltre().participantsMin(50));

        assertTrue(toutes.size() > 100);
        assertFalse(annulees.isEmpty());
        assertTrue(annulees.size() < toutes.size());
        assertFalse(alice.isEmpty());
        assertTrue(alice.stream().allMatch(r -> r.getUtilisateur().getEmail().equals("alice.dupont@example.com")));
        assertTrue(grandes.stream().allMatch(r -> r.getNombreParticipants() >= 50
                && r.getSalle().getNom().equals("Amphitheatre Turing")));
    }

    @Test
    public void rechercheParPeriode() {
        LocalDateTime debut = LocalDateTime.now().plusDays(2);
        LocalDateTime fin = LocalDateTime.now().plusDays(9);
        List<Reservation> periode = reservations.rechercher(new ReservationFiltre().apres(debut).avant(fin).salle("Salle B"));
        assertTrue(periode.stream().allMatch(r -> !r.getDateDebut().isBefore(debut) && !r.getDateFin().isAfter(fin)
                && r.getSalle().getNom().equals("Salle B")));
    }

    @Test
    public void sallesDisponiblesJpqlEtCriteria() {
        Reservation occupee = reservations.findBySalleNomApres("Salle A", LocalDateTime.now()).get(0);
        LocalDateTime debut = occupee.getDateDebut();
        LocalDateTime fin = occupee.getDateFin();

        List<Salle> jpql = salles.findSallesDisponibles(debut, fin, 1);
        List<Salle> criteria = salles.findSallesDisponiblesCriteria(debut, fin, 1);

        assertEquals(jpql, criteria);
        assertTrue(jpql.stream().noneMatch(s -> s.getNom().equals("Salle A")), "la Salle A est occupee sur ce creneau");
        assertTrue(salles.findSallesDisponibles(debut, fin, 100).stream().allMatch(s -> s.getCapacite() >= 100));

        // creneau totalement libre (nuit) : toutes les salles sont disponibles
        LocalDateTime nuit = LocalDateTime.now().plusDays(3).withHour(2);
        assertEquals(8, salles.findSallesDisponibles(nuit, nuit.plusHours(1), 1).size());
    }

    @Test
    public void jointureManyToManyEquipements() {
        List<Salle> visio = salles.findByEquipement("Visioconference");
        assertEquals(2, visio.size());
        assertTrue(visio.stream().allMatch(s -> s.getEquipements().stream().anyMatch(e -> e.getNom().equals("Visioconference"))));
    }

    @Test
    public void statistiquesOccupation() {
        List<StatistiqueSalle> statsSalles = salles.statistiquesOccupation();
        assertEquals(8, statsSalles.size(), "LEFT JOIN : toutes les salles apparaissent");
        long total = statsSalles.stream().mapToLong(StatistiqueSalle::getNombreReservations).sum();
        long attendu = reservations.rechercher(new ReservationFiltre().statut(StatutReservation.CONFIRMEE)).size();
        assertEquals(attendu, total);
        for (int i = 1; i < statsSalles.size(); i++) {
            assertTrue(statsSalles.get(i).getNombreReservations() <= statsSalles.get(i - 1).getNombreReservations());
        }
    }

    @Test
    public void reservationsAVenirUtilisateur() {
        List<Reservation> aVenir = reservations.findAVenirParUtilisateur("alice.dupont@example.com");
        assertFalse(aVenir.isEmpty());
        assertTrue(aVenir.stream().allMatch(r -> r.isActive() && r.getDateDebut().isAfter(LocalDateTime.now())));
    }

    @Test
    public void parametresProtegentDeLInjection() {
        assertTrue(reservations.findBySalleNomApres("Salle A' OR '1'='1", LocalDateTime.now().minusYears(1)).isEmpty());
        assertTrue(salles.findByNom("x' OR 1=1 --").isEmpty());
    }
}
