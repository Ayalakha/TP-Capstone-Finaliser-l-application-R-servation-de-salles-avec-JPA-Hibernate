package com.example.service;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;
import com.example.repository.ReservationRepository;
import com.example.util.TransactionHelper;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service metier de gestion des reservations.
 * <ul>
 *     <li>une transaction (et un EntityManager) par operation, rollback automatique en cas d'erreur ;</li>
 *     <li>regles metier : creneau valide, capacite de la salle, absence de chevauchement ;</li>
 *     <li>concurrence : verrouillage optimiste (@Version). A la creation/modification d'un creneau, la salle
 *     est verrouillee en OPTIMISTIC_FORCE_INCREMENT : deux reservations simultanees sur la meme salle
 *     ne peuvent pas etre validees toutes les deux, meme si chacune a verifie la disponibilite ;</li>
 *     <li>chaque erreur est journalisee puis traduite en exception metier au message explicite.</li>
 * </ul>
 */
public class ReservationService {

    private static final Logger LOGGER = Logger.getLogger(ReservationService.class.getName());

    public static final Duration DUREE_MAX = Duration.ofHours(12);

    private final EntityManagerFactory emf;

    public ReservationService(EntityManagerFactory emf) {
        this.emf = emf;
    }

    public Reservation creerReservation(Long utilisateurId, Long salleId, LocalDateTime debut, LocalDateTime fin,
                                        int nombreParticipants, String motif) {
        return executer("creation de la reservation", () -> {
            validerCreneau(debut, fin, true);
            Reservation reservation = TransactionHelper.inTransaction(emf, em -> {
                Salle salle = verrouillerSalle(em, salleId);
                Utilisateur utilisateur = em.find(Utilisateur.class, utilisateurId);
                if (utilisateur == null) {
                    throw new ReservationException("Utilisateur introuvable (id=" + utilisateurId + ")");
                }
                verifierCapacite(salle, nombreParticipants);
                verifierDisponibilite(em, salle, debut, fin, null);

                Reservation r = new Reservation(utilisateur, salle, debut, fin, nombreParticipants, motif);
                em.persist(r);
                return r;
            });
            LOGGER.info("Creation de la reservation reussie pour utilisateur : " + reservation.getUtilisateur().getEmail()
                    + " (id=" + reservation.getId() + ", salle=" + reservation.getSalle().getNom()
                    + ", " + reservation.getDateDebut() + " -> " + reservation.getDateFin() + ")");
            return reservation;
        });
    }

    /** Variante prenant une reservation non persistee (salle et utilisateur doivent avoir un id). */
    public Reservation createReservation(Reservation reservation) {
        if (reservation.getSalle() == null || reservation.getUtilisateur() == null) {
            throw new ReservationException("La salle et l'utilisateur sont obligatoires");
        }
        return creerReservation(reservation.getUtilisateur().getId(), reservation.getSalle().getId(),
                reservation.getDateDebut(), reservation.getDateFin(),
                reservation.getNombreParticipants(), reservation.getMotif());
    }

    /** Deplace une reservation sur un nouveau creneau (meme salle). */
    public Reservation modifierCreneau(Long reservationId, LocalDateTime debut, LocalDateTime fin) {
        return executer("modification du creneau", () -> {
            validerCreneau(debut, fin, true);
            Reservation reservation = TransactionHelper.inTransaction(emf, em -> {
                Reservation r = trouverOuEchouer(em, reservationId);
                if (!r.isActive()) {
                    throw new ReservationException("Impossible de modifier une reservation annulee (id=" + reservationId + ")");
                }
                Salle salle = verrouillerSalle(em, r.getSalle().getId());
                verifierDisponibilite(em, salle, debut, fin, reservationId);
                r.setDateDebut(debut);
                r.setDateFin(fin);
                return r;
            });
            LOGGER.info("Reservation " + reservationId + " deplacee : " + debut + " -> " + fin);
            return reservation;
        });
    }

    /**
     * Enregistre une reservation detachee modifiee (ex. formulaire). Si elle a ete modifiee
     * entre-temps par quelqu'un d'autre, sa version est perimee et une {@link ConcurrenceException} est levee.
     */
    public Reservation mettreAJour(Reservation detachee) {
        return executer("mise a jour de la reservation", () -> {
            if (detachee.getId() == null) {
                throw new ReservationException("La reservation n'a jamais ete enregistree");
            }
            validerCreneau(detachee.getDateDebut(), detachee.getDateFin(), false);
            Reservation reservation = TransactionHelper.inTransaction(emf, em -> {
                trouverOuEchouer(em, detachee.getId());
                if (detachee.isActive()) {
                    Salle salle = verrouillerSalle(em, detachee.getSalle().getId());
                    verifierCapacite(salle, detachee.getNombreParticipants());
                    verifierDisponibilite(em, salle, detachee.getDateDebut(), detachee.getDateFin(), detachee.getId());
                }
                // merge compare la version de l'objet detache a celle en base -> OptimisticLockException si perimee
                return em.merge(detachee);
            });
            LOGGER.info("Reservation " + reservation.getId() + " mise a jour (version " + reservation.getVersion() + ")");
            return reservation;
        });
    }

    /** Annule une reservation : le creneau est libere, l'historique conserve. */
    public void annulerReservation(Long reservationId) {
        executer("annulation de la reservation", () -> {
            TransactionHelper.inTransaction(emf, em -> {
                Reservation r = trouverOuEchouer(em, reservationId);
                if (r.getStatut() == StatutReservation.ANNULEE) {
                    LOGGER.fine("Reservation " + reservationId + " deja annulee");
                } else {
                    r.setStatut(StatutReservation.ANNULEE);
                }
                return r;
            });
            LOGGER.info("Reservation " + reservationId + " annulee");
            return null;
        });
    }

    /** Suppression definitive. */
    public void supprimerReservation(Long reservationId) {
        executer("suppression de la reservation", () -> {
            TransactionHelper.inTransaction(emf, em -> {
                em.remove(trouverOuEchouer(em, reservationId));
                return null;
            });
            LOGGER.info("Reservation " + reservationId + " supprimee");
            return null;
        });
    }

    /** Charge une reservation avec sa salle et son utilisateur (utilisable apres fermeture de l'EntityManager). */
    public Optional<Reservation> trouverReservation(Long reservationId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur "
                            + "WHERE r.id = :id", Reservation.class)
                    .setParameter("id", reservationId)
                    .getResultStream()
                    .findFirst();
        } finally {
            em.close();
        }
    }

    // ------------------------------------------------------------------ regles metier

    private static void validerCreneau(LocalDateTime debut, LocalDateTime fin, boolean futurObligatoire) {
        if (debut == null || fin == null) {
            throw new ReservationException("Les dates de debut et de fin sont obligatoires");
        }
        if (!fin.isAfter(debut)) {
            throw new ReservationException("La date de fin (" + fin + ") doit etre posterieure a la date de debut (" + debut + ")");
        }
        if (Duration.between(debut, fin).compareTo(DUREE_MAX) > 0) {
            throw new ReservationException("Une reservation ne peut pas depasser " + DUREE_MAX.toHours() + " heures");
        }
        if (futurObligatoire && debut.isBefore(LocalDateTime.now())) {
            throw new ReservationException("Impossible de reserver un creneau dans le passe (" + debut + ")");
        }
    }

    private static void verifierCapacite(Salle salle, int nombreParticipants) {
        if (nombreParticipants < 1) {
            throw new ReservationException("Le nombre de participants doit etre au moins 1");
        }
        if (nombreParticipants > salle.getCapacite()) {
            throw new ReservationException("La salle " + salle.getNom() + " ne peut accueillir que "
                    + salle.getCapacite() + " personnes (" + nombreParticipants + " demandees)");
        }
    }

    private static void verifierDisponibilite(EntityManager em, Salle salle, LocalDateTime debut, LocalDateTime fin,
                                              Long reservationExclue) {
        long conflits = new ReservationRepository(em).compterConflits(salle.getId(), debut, fin, reservationExclue);
        if (conflits > 0) {
            throw new ConflitReservationException("La salle " + salle.getNom() + " est deja reservee sur le creneau "
                    + debut + " -> " + fin + " (" + conflits + " reservation(s) en conflit)");
        }
    }

    /** Charge la salle et programme l'incrementation de sa version au commit (verrou optimiste force). */
    private static Salle verrouillerSalle(EntityManager em, Long salleId) {
        Salle salle = salleId == null ? null : em.find(Salle.class, salleId, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        if (salle == null) {
            throw new ReservationException("Salle introuvable (id=" + salleId + ")");
        }
        return salle;
    }

    private static Reservation trouverOuEchouer(EntityManager em, Long reservationId) {
        Reservation r = em.find(Reservation.class, reservationId);
        if (r == null) {
            throw new ReservationException("Reservation introuvable (id=" + reservationId + ")");
        }
        return r;
    }

    // ------------------------------------------------------------------ gestion des erreurs

    /**
     * Point unique de gestion des exceptions : aucune exception ne remonte sans avoir ete journalisee.
     * Erreurs metier -> WARNING ; conflit de version -> ConcurrenceException ; le reste -> SEVERE.
     */
    private <T> T executer(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (ReservationException e) {
            LOGGER.warning("Echec de la " + operation + " : " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            if (TransactionHelper.isOptimisticLockFailure(e)) {
                LOGGER.warning("Conflit de concurrence lors de la " + operation + " : " + e.getMessage());
                throw new ConcurrenceException("Conflit detecte : les donnees ont ete modifiees par un autre "
                        + "utilisateur entre-temps, veuillez reessayer.", e);
            }
            LOGGER.log(Level.SEVERE, "Erreur lors de la " + operation + " : " + e.getMessage(), e);
            throw new ReservationException("Erreur technique lors de la " + operation, e);
        }
    }
}
