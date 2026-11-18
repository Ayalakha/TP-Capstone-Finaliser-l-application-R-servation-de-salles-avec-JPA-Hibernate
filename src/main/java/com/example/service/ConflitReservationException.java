package com.example.service;

/** Le creneau demande chevauche une reservation existante de la meme salle. */
public class ConflitReservationException extends ReservationException {

    public ConflitReservationException(String message) {
        super(message);
    }
}
