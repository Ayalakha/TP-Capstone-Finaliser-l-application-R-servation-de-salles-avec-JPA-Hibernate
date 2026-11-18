package com.example.repository;

import com.example.model.StatutReservation;

import java.time.LocalDateTime;

/** Criteres optionnels de recherche de reservations (tous les champs null sont ignores). */
public class ReservationFiltre {

    private String nomSalle;
    private String emailUtilisateur;
    private LocalDateTime apres;
    private LocalDateTime avant;
    private StatutReservation statut;
    private Integer participantsMin;

    public ReservationFiltre salle(String nomSalle) {
        this.nomSalle = nomSalle;
        return this;
    }

    public ReservationFiltre utilisateur(String email) {
        this.emailUtilisateur = email;
        return this;
    }

    /** Reservations commencant a partir de cette date. */
    public ReservationFiltre apres(LocalDateTime date) {
        this.apres = date;
        return this;
    }

    /** Reservations se terminant au plus tard a cette date. */
    public ReservationFiltre avant(LocalDateTime date) {
        this.avant = date;
        return this;
    }

    public ReservationFiltre statut(StatutReservation statut) {
        this.statut = statut;
        return this;
    }

    public ReservationFiltre participantsMin(Integer participantsMin) {
        this.participantsMin = participantsMin;
        return this;
    }

    public String getNomSalle() {
        return nomSalle;
    }

    public String getEmailUtilisateur() {
        return emailUtilisateur;
    }

    public LocalDateTime getApres() {
        return apres;
    }

    public LocalDateTime getAvant() {
        return avant;
    }

    public StatutReservation getStatut() {
        return statut;
    }

    public Integer getParticipantsMin() {
        return participantsMin;
    }
}
