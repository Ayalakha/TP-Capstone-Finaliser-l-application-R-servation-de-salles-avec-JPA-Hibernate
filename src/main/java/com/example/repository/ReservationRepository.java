package com.example.repository;

import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;
import com.example.model.Utilisateur;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Requetes sur les reservations, en JPQL et en Criteria API.
 * Toutes les requetes sont parametrees (pas de concatenation : aucune injection possible)
 * et utilisent JOIN FETCH quand les associations sont ensuite affichees (evite le N+1).
 */
public class ReservationRepository {

    private final EntityManager em;

    public ReservationRepository(EntityManager em) {
        this.em = em;
    }

    /** JPQL : reservations d'une salle (par nom) commencant apres une date. */
    public List<Reservation> findBySalleNomApres(String nomSalle, LocalDateTime date) {
        String jpql = "SELECT r FROM Reservation r "
                + "JOIN FETCH r.salle s JOIN FETCH r.utilisateur "
                + "WHERE s.nom = :nomSalle AND r.dateDebut > :date "
                + "ORDER BY r.dateDebut";
        return em.createQuery(jpql, Reservation.class)
                .setParameter("nomSalle", nomSalle)
                .setParameter("date", date)
                .getResultList();
    }

    /** Criteria API : meme requete que {@link #findBySalleNomApres}. */
    public List<Reservation> findBySalleNomApresCriteria(String nomSalle, LocalDateTime date) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
        Root<Reservation> root = cq.from(Reservation.class);
        Join<Reservation, Salle> salle = fetchJoin(root, "salle");
        root.fetch("utilisateur");

        Predicate predicate = cb.and(
                cb.equal(salle.get("nom"), nomSalle),
                cb.greaterThan(root.get("dateDebut"), date)
        );
        cq.select(root).where(predicate).orderBy(cb.asc(root.get("dateDebut")));
        return em.createQuery(cq).getResultList();
    }

    /** Criteria API dynamique : seuls les criteres renseignes dans le filtre sont appliques. */
    public List<Reservation> rechercher(ReservationFiltre filtre) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Reservation> cq = cb.createQuery(Reservation.class);
        Root<Reservation> root = cq.from(Reservation.class);
        Join<Reservation, Salle> salle = fetchJoin(root, "salle");
        Join<Reservation, Utilisateur> utilisateur = fetchJoin(root, "utilisateur");

        List<Predicate> predicates = new ArrayList<>();
        if (filtre.getNomSalle() != null) {
            predicates.add(cb.equal(salle.get("nom"), filtre.getNomSalle()));
        }
        if (filtre.getEmailUtilisateur() != null) {
            predicates.add(cb.equal(cb.lower(utilisateur.get("email")), filtre.getEmailUtilisateur().toLowerCase()));
        }
        if (filtre.getApres() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("dateDebut"), filtre.getApres()));
        }
        if (filtre.getAvant() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("dateFin"), filtre.getAvant()));
        }
        if (filtre.getStatut() != null) {
            predicates.add(cb.equal(root.get("statut"), filtre.getStatut()));
        }
        if (filtre.getParticipantsMin() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("nombreParticipants"), filtre.getParticipantsMin()));
        }
        cq.select(root)
                .where(predicates.toArray(new Predicate[0]))
                .orderBy(cb.asc(root.get("dateDebut")), cb.asc(salle.get("nom")));
        return em.createQuery(cq).getResultList();
    }

    /** Nombre de reservations actives d'une salle qui chevauchent [debut, fin[ (hors reservation exclue). */
    public long compterConflits(Long salleId, LocalDateTime debut, LocalDateTime fin, Long reservationExclue) {
        return em.createQuery(
                        "SELECT COUNT(r) FROM Reservation r "
                                + "WHERE r.salle.id = :salleId AND r.statut = :statut "
                                + "AND r.dateDebut < :fin AND r.dateFin > :debut "
                                + "AND r.id <> :exclue", Long.class)
                .setParameter("salleId", salleId)
                .setParameter("statut", StatutReservation.CONFIRMEE)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .setParameter("exclue", reservationExclue == null ? -1L : reservationExclue)
                .getSingleResult();
    }

    /** Reservations a venir d'un utilisateur. */
    public List<Reservation> findAVenirParUtilisateur(String email) {
        return em.createQuery(
                        "SELECT r FROM Reservation r JOIN FETCH r.salle JOIN FETCH r.utilisateur u "
                                + "WHERE u.email = :email AND r.statut = :statut AND r.dateDebut > :maintenant "
                                + "ORDER BY r.dateDebut", Reservation.class)
                .setParameter("email", email)
                .setParameter("statut", StatutReservation.CONFIRMEE)
                .setParameter("maintenant", LocalDateTime.now())
                .getResultList();
    }

    /**
     * root.fetch(...) renvoie un Fetch ; avec Hibernate c'est aussi un Join, ce qui permet
     * de filtrer sur l'association chargee sans ajouter une seconde jointure.
     */
    @SuppressWarnings("unchecked")
    private static <X, Y> Join<X, Y> fetchJoin(Root<X> root, String attribut) {
        return (Join<X, Y>) root.fetch(attribut, JoinType.INNER);
    }
}
