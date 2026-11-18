package com.example.model;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Reservation d'une salle par un utilisateur sur un creneau.
 * Donnee tres volatile : volontairement NON mise en cache de second niveau.
 */
@Entity
@Table(name = "reservations", indexes = {
        @Index(name = "idx_reservation_salle_periode", columnList = "salle_id, date_debut, date_fin"),
        @Index(name = "idx_reservation_utilisateur", columnList = "utilisateur_id")
})
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "salle_id", nullable = false)
    private Salle salle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private Utilisateur utilisateur;

    @Column(name = "date_debut", nullable = false)
    private LocalDateTime dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDateTime dateFin;

    @Column(name = "nombre_participants", nullable = false)
    private int nombreParticipants = 1;

    @Column(length = 255)
    private String motif;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatutReservation statut = StatutReservation.CONFIRMEE;

    @Version
    private int version;

    public Reservation() {
    }

    public Reservation(Utilisateur utilisateur, Salle salle, LocalDateTime dateDebut, LocalDateTime dateFin,
                       int nombreParticipants, String motif) {
        this.utilisateur = utilisateur;
        this.salle = salle;
        this.dateDebut = dateDebut;
        this.dateFin = dateFin;
        this.nombreParticipants = nombreParticipants;
        this.motif = motif;
    }

    /** Garde-fou au niveau entite : la base a aussi une contrainte CHECK equivalente. */
    @PrePersist
    @PreUpdate
    private void verifierCoherence() {
        if (dateDebut == null || dateFin == null || !dateFin.isAfter(dateDebut)) {
            throw new IllegalStateException("La date de fin doit etre posterieure a la date de debut");
        }
    }

    /** Vrai si cette reservation chevauche l'intervalle [debut, fin[. */
    public boolean chevauche(LocalDateTime debut, LocalDateTime fin) {
        return dateDebut.isBefore(fin) && dateFin.isAfter(debut);
    }

    public boolean isActive() {
        return statut == StatutReservation.CONFIRMEE;
    }

    public Long getId() {
        return id;
    }

    public Salle getSalle() {
        return salle;
    }

    public void setSalle(Salle salle) {
        this.salle = salle;
    }

    public Utilisateur getUtilisateur() {
        return utilisateur;
    }

    public void setUtilisateur(Utilisateur utilisateur) {
        this.utilisateur = utilisateur;
    }

    public LocalDateTime getDateDebut() {
        return dateDebut;
    }

    public void setDateDebut(LocalDateTime dateDebut) {
        this.dateDebut = dateDebut;
    }

    public LocalDateTime getDateFin() {
        return dateFin;
    }

    public void setDateFin(LocalDateTime dateFin) {
        this.dateFin = dateFin;
    }

    public int getNombreParticipants() {
        return nombreParticipants;
    }

    public void setNombreParticipants(int nombreParticipants) {
        this.nombreParticipants = nombreParticipants;
    }

    public String getMotif() {
        return motif;
    }

    public void setMotif(String motif) {
        this.motif = motif;
    }

    public StatutReservation getStatut() {
        return statut;
    }

    public void setStatut(StatutReservation statut) {
        this.statut = statut;
    }

    public int getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Reservation)) return false;
        Reservation other = (Reservation) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Reservation.class.hashCode();
    }

    @Override
    public String toString() {
        return "Reservation{id=" + id + ", debut=" + dateDebut + ", fin=" + dateFin
                + ", statut=" + statut + ", version=" + version + "}";
    }
}
