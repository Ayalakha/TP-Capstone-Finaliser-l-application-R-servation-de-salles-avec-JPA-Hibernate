package com.example.service;

/**
 * Modification concurrente detectee par le verrouillage optimiste (@Version) :
 * les donnees ont change depuis leur lecture, l'operation peut etre retentee.
 */
public class ConcurrenceException extends ReservationException {

    public ConcurrenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
