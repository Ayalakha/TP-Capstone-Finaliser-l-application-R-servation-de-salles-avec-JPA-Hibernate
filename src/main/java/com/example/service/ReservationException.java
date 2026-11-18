package com.example.service;

/** Erreur metier lors d'une operation de reservation (message destine a l'utilisateur). */
public class ReservationException extends RuntimeException {

    public ReservationException(String message) {
        super(message);
    }

    public ReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
