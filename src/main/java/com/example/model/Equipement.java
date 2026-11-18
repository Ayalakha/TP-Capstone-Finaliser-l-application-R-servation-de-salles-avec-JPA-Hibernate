package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;

import javax.persistence.*;
import java.util.Objects;

/**
 * Equipement d'une salle (videoprojecteur, tableau blanc...).
 * Donnee de reference qui ne change jamais : entite @Immutable et cache READ_ONLY,
 * la strategie la plus performante.
 */
@Entity
@Table(name = "equipements")
@Immutable
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
public class Equipement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nom;

    @Column(length = 255)
    private String description;

    protected Equipement() {
    }

    public Equipement(String nom, String description) {
        this.nom = nom;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public String getNom() {
        return nom;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Equipement)) return false;
        Equipement other = (Equipement) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Equipement.class.hashCode();
    }

    @Override
    public String toString() {
        return nom;
    }
}
