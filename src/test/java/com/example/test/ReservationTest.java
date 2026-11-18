package com.example.test;

import com.example.model.*;
import com.example.service.ConcurrenceException;
import com.example.service.ConflitReservationException;
import com.example.service.ReservationException;
import com.example.service.ReservationService;
import com.example.util.TransactionHelper;
import org.junit.jupiter.api.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceException;
import javax.persistence.RollbackException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenarios fonctionnels : creation, modification, annulation, suppression,
 * cas d'erreur (conflit, dates, capacite) et verrouillage optimiste.
 * Chaque test utilise un jour different (TestSupport.creneau) : les tests sont independants.
 */
public class ReservationTest {

    private static final String BASE = "reservation_test";

    private static EntityManagerFactory emf;
    private static ReservationService service;
    private EntityManager em;

    private static Long aliceId;
    private static Long brunoId;
    private static Long salleAId;      // 20 places
    private static Long lovelaceId;    // 8 places

    @BeforeAll
    public static void init() throws Exception {
        emf = TestSupport.creerBase(BASE, true);
        service = new ReservationService(emf);
        EntityManager setup = emf.createEntityManager();
        try {
            aliceId = idUtilisateur(setup, "alice.dupont@example.com");
            brunoId = idUtilisateur(setup, "bruno.martin@example.com");
            salleAId = idSalle(setup, "Salle A");
            lovelaceId = idSalle(setup, "Salle Lovelace");
        } finally {
            setup.close();
        }
    }

    @BeforeEach
    public void setUp() {
        em = emf.createEntityManager();
    }

    @AfterEach
    public void tearDown() {
        if (em != null) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            em.close();
        }
    }

    @AfterAll
    public static void close() {
        TestSupport.detruireBase(emf, BASE);
    }

    // ----------------------------------------------------------------- cas nominaux

    @Test
    public void testCreateReservation() {
        em.getTransaction().begin();

        Utilisateur user = new Utilisateur();
        user.setNom("Test User");
        user.setEmail("test.user@example.com");
        em.persist(user);

        Salle salle = em.find(Salle.class, 1L); // creee par DataInitializer

        Reservation reservation = new Reservation();
        reservation.setUtilisateur(user);
        reservation.setSalle(salle);
        reservation.setDateDebut(LocalDateTime.now().plusDays(2));
        reservation.setDateFin(LocalDateTime.now().plusDays(2).plusHours(1));

        em.persist(reservation);
        em.getTransaction().commit();

        Assertions.assertNotNull(reservation.getId());
        assertEquals(StatutReservation.CONFIRMEE, reservation.getStatut());
        assertEquals(0, reservation.getVersion());
    }

    @Test
    public void creerReservationViaService() {
        LocalDateTime debut = TestSupport.creneau(1, 9);
        Reservation r = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(2), 10, "Reunion");

        assertNotNull(r.getId());
        Reservation relue = service.trouverReservation(r.getId()).orElseThrow();
        assertEquals("Salle A", relue.getSalle().getNom());
        assertEquals("alice.dupont@example.com", relue.getUtilisateur().getEmail());
        assertEquals(debut, relue.getDateDebut());
        assertEquals(10, relue.getNombreParticipants());
    }

    @Test
    public void creneauxAdjacentsAutorises() {
        LocalDateTime debut = TestSupport.creneau(2, 9);
        service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "9h-10h");
        // commence exactement a la fin de la precedente : pas de chevauchement
        Reservation suivante = service.creerReservation(brunoId, salleAId, debut.plusHours(1), debut.plusHours(2), 5, "10h-11h");
        assertNotNull(suivante.getId());
    }

    @Test
    public void memeCreneauDansDeuxSallesDifferentes() {
        LocalDateTime debut = TestSupport.creneau(3, 14);
        service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "Salle A");
        assertNotNull(service.creerReservation(brunoId, lovelaceId, debut, debut.plusHours(1), 5, "Lovelace").getId());
    }

    @Test
    public void modifierCreneau() {
        LocalDateTime debut = TestSupport.creneau(4, 9);
        Reservation r = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "A deplacer");

        Reservation modifiee = service.modifierCreneau(r.getId(), debut.plusHours(3), debut.plusHours(5));

        assertEquals(debut.plusHours(3), modifiee.getDateDebut());
        assertEquals(1, modifiee.getVersion(), "la version doit etre incrementee a chaque modification");
    }

    @Test
    public void modifierCreneauNeSeConsidererPasEnConflitAvecLuiMeme() {
        LocalDateTime debut = TestSupport.creneau(5, 9);
        Reservation r = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(2), 5, "Prolongation");
        // allonger la reservation chevauche l'ancien creneau de la meme reservation : autorise
        Reservation modifiee = service.modifierCreneau(r.getId(), debut, debut.plusHours(3));
        assertEquals(debut.plusHours(3), modifiee.getDateFin());
    }

    @Test
    public void annulerLibereLeCreneau() {
        LocalDateTime debut = TestSupport.creneau(6, 9);
        Reservation r = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "Annulee");

        service.annulerReservation(r.getId());

        assertEquals(StatutReservation.ANNULEE, service.trouverReservation(r.getId()).orElseThrow().getStatut());
        assertNotNull(service.creerReservation(brunoId, salleAId, debut, debut.plusHours(1), 5, "Reprise").getId());
    }

    @Test
    public void supprimerReservation() {
        LocalDateTime debut = TestSupport.creneau(7, 9);
        Reservation r = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "A supprimer");

        service.supprimerReservation(r.getId());

        assertTrue(service.trouverReservation(r.getId()).isEmpty());
        assertThrows(ReservationException.class, () -> service.supprimerReservation(r.getId()));
    }

    // ----------------------------------------------------------------- cas d'erreur

    @Test
    public void reservationEnConflitRefusee() {
        LocalDateTime debut = TestSupport.creneau(8, 10);
        service.creerReservation(aliceId, salleAId, debut, debut.plusHours(2), 5, "Premiere");

        ConflitReservationException ex = assertThrows(ConflitReservationException.class,
                () -> service.creerReservation(brunoId, salleAId, debut.plusHours(1), debut.plusHours(3), 5, "Chevauche"));
        assertTrue(ex.getMessage().contains("Salle A"));

        // englobante
        assertThrows(ConflitReservationException.class,
                () -> service.creerReservation(brunoId, salleAId, debut.minusHours(1), debut.plusHours(4), 5, "Englobe"));
    }

    @Test
    public void modificationVersCreneauOccupeRefusee() {
        LocalDateTime debut = TestSupport.creneau(9, 9);
        service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "Occupe");
        Reservation autre = service.creerReservation(brunoId, salleAId, debut.plusHours(2), debut.plusHours(3), 5, "A deplacer");

        assertThrows(ConflitReservationException.class,
                () -> service.modifierCreneau(autre.getId(), debut, debut.plusHours(1)));
        assertEquals(debut.plusHours(2), service.trouverReservation(autre.getId()).orElseThrow().getDateDebut(),
                "la transaction a ete annulee : la reservation n'a pas bouge");
    }

    @Test
    public void datesInvalidesRefusees() {
        LocalDateTime debut = TestSupport.creneau(10, 9);
        assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, salleAId, debut, debut.minusHours(1), 5, "Fin avant debut"));
        assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, salleAId, debut, debut, 5, "Duree nulle"));
        assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, salleAId, debut, debut.plusHours(13), 5, "Trop longue"));
        assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, salleAId, LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().minusDays(1).plusHours(1), 5, "Passe"));
    }

    @Test
    public void capaciteDepasseeRefusee() {
        LocalDateTime debut = TestSupport.creneau(11, 9);
        ReservationException ex = assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, lovelaceId, debut, debut.plusHours(1), 9, "Trop nombreux"));
        assertTrue(ex.getMessage().contains("8"));
    }

    @Test
    public void salleOuUtilisateurIntrouvable() {
        LocalDateTime debut = TestSupport.creneau(12, 9);
        assertThrows(ReservationException.class,
                () -> service.creerReservation(aliceId, 9999L, debut, debut.plusHours(1), 1, "?"));
        assertThrows(ReservationException.class,
                () -> service.creerReservation(9999L, salleAId, debut, debut.plusHours(1), 1, "?"));
    }

    @Test
    public void contrainteEntiteDatesIncoherentes() {
        em.getTransaction().begin();
        Reservation r = new Reservation(em.find(Utilisateur.class, aliceId), em.find(Salle.class, salleAId),
                TestSupport.creneau(13, 10), TestSupport.creneau(13, 9), 1, "Invalide");
        assertThrows(IllegalStateException.class, () -> em.persist(r));
    }

    @Test
    public void emailUtilisateurUnique() {
        // IDENTITY : l'INSERT part des persist() -> violation de l'index unique idx_utilisateur_email (migration v2.0)
        assertThrows(PersistenceException.class, () -> TransactionHelper.inTransaction(emf, e -> {
            e.persist(new Utilisateur("Doublon", "alice.dupont@example.com", "Test"));
            return null;
        }));
    }

    // ----------------------------------------------------------------- verrouillage optimiste

    @Test
    public void optimisticLockingAvecDeuxEntityManagers() {
        LocalDateTime debut = TestSupport.creneau(14, 9);
        Long id = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "Original").getId();

        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();
        try {
            em1.getTransaction().begin();
            em2.getTransaction().begin();
            Reservation vue1 = em1.find(Reservation.class, id);
            Reservation vue2 = em2.find(Reservation.class, id);
            assertEquals(vue1.getVersion(), vue2.getVersion());

            vue1.setMotif("Modifie par em1");
            em1.getTransaction().commit();

            vue2.setMotif("Modifie par em2");
            RollbackException ex = assertThrows(RollbackException.class, () -> em2.getTransaction().commit());
            assertTrue(TransactionHelper.isOptimisticLockFailure(ex));
        } finally {
            em1.close();
            em2.close();
        }
        assertEquals("Modifie par em1", service.trouverReservation(id).orElseThrow().getMotif());
    }

    @Test
    public void optimisticLockingViaService() {
        LocalDateTime debut = TestSupport.creneau(15, 9);
        Long id = service.creerReservation(aliceId, salleAId, debut, debut.plusHours(1), 5, "Original").getId();

        Reservation copie1 = service.trouverReservation(id).orElseThrow();
        Reservation copie2 = service.trouverReservation(id).orElseThrow();

        copie1.setMotif("Version 1");
        assertEquals(1, service.mettreAJour(copie1).getVersion());

        copie2.setMotif("Version 2 (perimee)");
        assertThrows(ConcurrenceException.class, () -> service.mettreAJour(copie2));

        // apres rechargement, la mise a jour passe
        Reservation fraiche = service.trouverReservation(id).orElseThrow();
        fraiche.setMotif("Version 2");
        assertEquals("Version 2", service.mettreAJour(fraiche).getMotif());
    }

    /**
     * Deux transactions verifient en parallele que le creneau est libre et inserent chacune une reservation.
     * Le verrou OPTIMISTIC_FORCE_INCREMENT sur la salle fait echouer la seconde : pas de double reservation.
     */
    @Test
    public void reservationsSimultaneesSurLaMemeSalle() {
        LocalDateTime debut = TestSupport.creneau(16, 9);
        EntityManager em1 = emf.createEntityManager();
        EntityManager em2 = emf.createEntityManager();
        try {
            em1.getTransaction().begin();
            em2.getTransaction().begin();
            Salle s1 = em1.find(Salle.class, salleAId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            Salle s2 = em2.find(Salle.class, salleAId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            em1.persist(new Reservation(em1.find(Utilisateur.class, aliceId), s1, debut, debut.plusHours(1), 2, "T1"));
            em2.persist(new Reservation(em2.find(Utilisateur.class, brunoId), s2, debut, debut.plusHours(1), 2, "T2"));

            em1.getTransaction().commit();
            RollbackException ex = assertThrows(RollbackException.class, () -> em2.getTransaction().commit());
            assertTrue(TransactionHelper.isOptimisticLockFailure(ex));
        } finally {
            em1.close();
            em2.close();
        }
        Long nb = em.createQuery("SELECT COUNT(r) FROM Reservation r WHERE r.salle.id = :s AND r.dateDebut = :d", Long.class)
                .setParameter("s", salleAId).setParameter("d", debut).getSingleResult();
        assertEquals(1L, nb, "une seule des deux reservations doit avoir ete enregistree");
    }

    // ----------------------------------------------------------------- utilitaires

    private static Long idUtilisateur(EntityManager em, String email) {
        return em.createQuery("SELECT u.id FROM Utilisateur u WHERE u.email = :e", Long.class)
                .setParameter("e", email).getSingleResult();
    }

    private static Long idSalle(EntityManager em, String nom) {
        return em.createQuery("SELECT s.id FROM Salle s WHERE s.nom = :n", Long.class)
                .setParameter("n", nom).getSingleResult();
    }
}
