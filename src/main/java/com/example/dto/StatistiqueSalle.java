package com.example.dto;

/** Projection JPQL (SELECT NEW ...) : nombre de reservations actives par salle. */
public class StatistiqueSalle {

    private final String nomSalle;
    private final long nombreReservations;

    public StatistiqueSalle(String nomSalle, Long nombreReservations) {
        this.nomSalle = nomSalle;
        this.nombreReservations = nombreReservations == null ? 0 : nombreReservations;
    }

    public String getNomSalle() {
        return nomSalle;
    }

    public long getNombreReservations() {
        return nombreReservations;
    }

    @Override
    public String toString() {
        return nomSalle + " : " + nombreReservations + " reservation(s)";
    }
}
