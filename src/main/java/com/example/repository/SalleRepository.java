package com.example.repository;

import com.example.dto.StatistiqueSalle;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.StatutReservation;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Requetes sur les salles. Les requetes de consultation frequentes utilisent le cache de requetes. */
public class SalleRepository {

    /** Hint JPA standard d'Hibernate pour placer le resultat dans le cache de requetes. */
    public static final String HINT_CACHEABLE = "org.hibernate.cacheable";

    private final EntityManager em;

    public SalleRepository(EntityManager em) {
        this.em = em;
    }

    /** Liste des salles (requete mise en cache : resultat reutilise tant que la table n'est pas modifiee). */
    public List<Salle> findAll() {
        return em.createQuery("SELECT s FROM Salle s ORDER BY s.nom", Salle.class)
                .setHint(HINT_CACHEABLE, true)
                .getResultList();
    }

    public Optional<Salle> findByNom(String nom) {
        return em.createQuery("SELECT s FROM Salle s WHERE s.nom = :nom", Salle.class)
                .setParameter("nom", nom)
                .setHint(HINT_CACHEABLE, true)
                .getResultStream()
                .findFirst();
    }

    /** JPQL : salles d'au moins capaciteMin places sans reservation active sur [debut, fin[. */
    public List<Salle> findSallesDisponibles(LocalDateTime debut, LocalDateTime fin, int capaciteMin) {
        return em.createQuery(
                        "SELECT s FROM Salle s WHERE s.capacite >= :capacite AND NOT EXISTS ("
                                + "  SELECT r FROM Reservation r WHERE r.salle = s AND r.statut = :statut"
                                + "  AND r.dateDebut < :fin AND r.dateFin > :debut) "
                                + "ORDER BY s.capacite, s.nom", Salle.class)
                .setParameter("capacite", capaciteMin)
                .setParameter("statut", StatutReservation.CONFIRMEE)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .getResultList();
    }

    /** Criteria API : meme requete que {@link #findSallesDisponibles}, avec une sous-requete. */
    public List<Salle> findSallesDisponiblesCriteria(LocalDateTime debut, LocalDateTime fin, int capaciteMin) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Salle> cq = cb.createQuery(Salle.class);
        Root<Salle> salle = cq.from(Salle.class);

        Subquery<Long> sub = cq.subquery(Long.class);
        Root<Reservation> r = sub.from(Reservation.class);
        sub.select(r.get("id")).where(
                cb.equal(r.get("salle"), salle),
                cb.equal(r.get("statut"), StatutReservation.CONFIRMEE),
                cb.lessThan(r.get("dateDebut"), fin),
                cb.greaterThan(r.get("dateFin"), debut));

        cq.select(salle)
                .where(cb.greaterThanOrEqualTo(salle.get("capacite"), capaciteMin), cb.not(cb.exists(sub)))
                .orderBy(cb.asc(salle.get("capacite")), cb.asc(salle.get("nom")));
        return em.createQuery(cq).getResultList();
    }

    /** Salles possedant un equipement donne (jointure sur la relation ManyToMany). */
    public List<Salle> findByEquipement(String nomEquipement) {
        return em.createQuery(
                        "SELECT DISTINCT s FROM Salle s JOIN s.equipements e WHERE e.nom = :nom ORDER BY s.nom", Salle.class)
                .setParameter("nom", nomEquipement)
                .getResultList();
    }

    /** Projection DTO : nombre de reservations actives par salle (salles sans reservation incluses). */
    public List<StatistiqueSalle> statistiquesOccupation() {
        return em.createQuery(
                        "SELECT NEW com.example.dto.StatistiqueSalle(s.nom, COUNT(r)) "
                                + "FROM Salle s LEFT JOIN s.reservations r ON r.statut = :statut "
                                + "GROUP BY s.id, s.nom ORDER BY COUNT(r) DESC, s.nom", StatistiqueSalle.class)
                .setParameter("statut", StatutReservation.CONFIRMEE)
                .getResultList();
    }
}
