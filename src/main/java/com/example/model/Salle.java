package com.example.model;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Salle reservable. Entite tres souvent lue (affichage, recherche de disponibilites) :
 * elle est placee dans le cache de second niveau en READ_WRITE.
 */
@Entity
@Table(name = "salles")
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Salle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nom;

    @Column(nullable = false)
    private int capacite;

    @Column(length = 50)
    private String batiment;

    private Integer etage;

    /** Colonne ajoutee par la migration v2.0. */
    @Column(length = 255)
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "salle_equipements",
            joinColumns = @JoinColumn(name = "salle_id"),
            inverseJoinColumns = @JoinColumn(name = "equipement_id"))
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    private Set<Equipement> equipements = new HashSet<>();

    @OneToMany(mappedBy = "salle")
    private List<Reservation> reservations = new ArrayList<>();

    @Version
    private int version;

    public Salle() {
    }

    public Salle(String nom, int capacite, String batiment, Integer etage, String description) {
        this.nom = nom;
        this.capacite = capacite;
        this.batiment = batiment;
        this.etage = etage;
        this.description = description;
    }

    public void addEquipement(Equipement equipement) {
        equipements.add(equipement);
    }

    public Long getId() {
        return id;
    }

    public String getNom() {
        return nom;
    }

    public void setNom(String nom) {
        this.nom = nom;
    }

    public int getCapacite() {
        return capacite;
    }

    public void setCapacite(int capacite) {
        this.capacite = capacite;
    }

    public String getBatiment() {
        return batiment;
    }

    public void setBatiment(String batiment) {
        this.batiment = batiment;
    }

    public Integer getEtage() {
        return etage;
    }

    public void setEtage(Integer etage) {
        this.etage = etage;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Equipement> getEquipements() {
        return equipements;
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
        if (!(o instanceof Salle)) return false;
        Salle other = (Salle) o;
        return getId() != null && Objects.equals(getId(), other.getId());
    }

    @Override
    public int hashCode() {
        return Salle.class.hashCode();
    }

    @Override
    public String toString() {
        return "Salle{id=" + id + ", nom='" + nom + "', capacite=" + capacite + "}";
    }
}
