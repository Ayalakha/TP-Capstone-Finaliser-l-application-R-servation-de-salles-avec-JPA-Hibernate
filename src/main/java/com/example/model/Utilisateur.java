package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utilisateur pouvant effectuer des reservations.
 * Donnee lue frequemment et rarement modifiee : cache de second niveau READ_WRITE.
 */
@Entity
@Table(name = "utilisateurs")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Utilisateur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 100)
    private String nom;

    @Column(length = 100)
    private String departement;

    @OneToMany(mappedBy = "utilisateur")
    private List<Reservation> reservations = new ArrayList<>();

    @Version
    private int version;

    public Utilisateur() {
    }

    public Utilisateur(String nom, String email, String departement) {
        this.nom = nom;
        this.email = email;
        this.departement = departement;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public String getDepartement() {
        return departement;
    }

    public void setDepartement(String departement) {
        this.departement = departement;
    }

    public List<Reservation> getReservations() {
        return reservations;
    }

    public int getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Utilisateur)) return false;
        Utilisateur other = (Utilisateur) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Utilisateur.class.hashCode();
    }

    @Override
    public String toString() {
        return "Utilisateur{id=" + id + ", nom='" + nom + "', email='" + email + "'}";
    }
}
