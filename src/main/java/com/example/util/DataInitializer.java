package com.example.util;

import com.example.model.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Insere un jeu de donnees realiste : 12 utilisateurs, 6 equipements, 8 salles et
 * environ 150 reservations (historique sur 7 jours + 3 semaines a venir, quelques annulations).
 * <p>
 * Les dates sont calculees a partir de la date du jour pour que les reservations restent
 * toujours pertinentes, et le generateur aleatoire a une graine fixe : le jeu de donnees
 * est reproductible d'une execution a l'autre. L'initialisation est idempotente.
 */
public class DataInitializer {

    private static final Logger LOGGER = Logger.getLogger(DataInitializer.class.getName());

    private static final String[] MOTIFS = {
            "Reunion d'equipe", "Point projet", "Formation JPA", "Entretien de recrutement",
            "Atelier de conception", "Revue de code", "Comite de pilotage", "Demonstration client",
            "Soutenance", "Sprint planning", "Retrospective", "Seminaire"
    };

    private final EntityManager em;
    private final Random random = new Random(42);

    public DataInitializer(EntityManager em) {
        this.em = em;
    }

    /** @return true si des donnees ont ete inserees, false si la base en contenait deja */
    public boolean initData() {
        Long existing = em.createQuery("SELECT COUNT(u) FROM Utilisateur u", Long.class).getSingleResult();
        if (existing > 0) {
            LOGGER.info("Jeu de donnees deja present (" + existing + " utilisateurs) : initialisation ignoree.");
            return false;
        }

        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            List<Utilisateur> utilisateurs = creerUtilisateurs();
            List<Equipement> equipements = creerEquipements();
            List<Salle> salles = creerSalles(equipements);
            int nbReservations = creerReservations(utilisateurs, salles);
            tx.commit();
            LOGGER.info(String.format("Jeu de donnees cree : %d utilisateurs, %d equipements, %d salles, %d reservations.",
                    utilisateurs.size(), equipements.size(), salles.size(), nbReservations));
            return true;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            LOGGER.severe("Echec de l'initialisation des donnees : " + e.getMessage());
            throw e;
        } finally {
            em.clear();
        }
    }

    private List<Utilisateur> creerUtilisateurs() {
        String[][] data = {
                {"Alice Dupont", "alice.dupont@example.com", "Informatique"},
                {"Bruno Martin", "bruno.martin@example.com", "Ressources humaines"},
                {"Chloe Bernard", "chloe.bernard@example.com", "Marketing"},
                {"David Petit", "david.petit@example.com", "Informatique"},
                {"Emma Robert", "emma.robert@example.com", "Direction"},
                {"Farid Benali", "farid.benali@example.com", "Finance"},
                {"Gabrielle Moreau", "gabrielle.moreau@example.com", "Juridique"},
                {"Hugo Laurent", "hugo.laurent@example.com", "Informatique"},
                {"Ines Garcia", "ines.garcia@example.com", "Commercial"},
                {"Julien Roux", "julien.roux@example.com", "Formation"},
                {"Karima El Idrissi", "karima.elidrissi@example.com", "Recherche"},
                {"Lucas Fontaine", "lucas.fontaine@example.com", "Commercial"}
        };
        List<Utilisateur> result = new ArrayList<>();
        for (String[] d : data) {
            Utilisateur u = new Utilisateur(d[0], d[1], d[2]);
            em.persist(u);
            result.add(u);
        }
        return result;
    }

    private List<Equipement> creerEquipements() {
        String[][] data = {
                {"Videoprojecteur", "Videoprojecteur HDMI 1080p"},
                {"Tableau blanc", "Tableau blanc effacable"},
                {"Visioconference", "Camera grand angle + micro de table"},
                {"Ecran interactif", "Ecran tactile 75 pouces"},
                {"Sonorisation", "Micros sans fil et enceintes"},
                {"Postes informatiques", "Postes de travail individuels"}
        };
        List<Equipement> result = new ArrayList<>();
        for (String[] d : data) {
            Equipement e = new Equipement(d[0], d[1]);
            em.persist(e);
            result.add(e);
        }
        return result;
    }

    private List<Salle> creerSalles(List<Equipement> eq) {
        List<Salle> salles = new ArrayList<>();
        salles.add(salle("Salle A", 20, "Batiment A", 1, "Salle de reunion polyvalente", eq, 0, 1));
        salles.add(salle("Salle B", 12, "Batiment A", 1, "Petite salle de reunion", eq, 1));
        salles.add(salle("Amphitheatre Turing", 150, "Batiment B", 0, "Amphitheatre pour conferences", eq, 0, 4));
        salles.add(salle("Salle Lovelace", 8, "Batiment B", 2, "Salle de brainstorming", eq, 1, 3));
        salles.add(salle("Salle Hopper", 30, "Batiment C", 1, "Salle de formation", eq, 0, 1, 3));
        salles.add(salle("Laboratoire Informatique", 24, "Batiment C", 2, "Salle de TP equipee", eq, 0, 5));
        salles.add(salle("Salle Visio", 6, "Batiment A", 3, "Salle dediee aux visioconferences", eq, 2, 3));
        salles.add(salle("Salle du Conseil", 16, "Batiment A", 4, "Salle de direction", eq, 0, 2, 3));
        for (Salle s : salles) {
            em.persist(s);
        }
        return salles;
    }

    private Salle salle(String nom, int capacite, String batiment, int etage, String description,
                        List<Equipement> equipements, int... indexEquipements) {
        Salle s = new Salle(nom, capacite, batiment, etage, description);
        for (int i : indexEquipements) {
            s.addEquipement(equipements.get(i));
        }
        return s;
    }

    private int creerReservations(List<Utilisateur> utilisateurs, List<Salle> salles) {
        List<Reservation> planifiees = new ArrayList<>();

        // Reservation "de reference" du TP : Alice, Salle A, demain de 10h a 12h.
        LocalDateTime demain10h = LocalDate.now().plusDays(1).atTime(10, 0);
        planifiees.add(new Reservation(utilisateurs.get(0), salles.get(0), demain10h, demain10h.plusHours(2),
                8, "Reunion d'equipe"));

        // Historique (7 derniers jours) + 3 semaines a venir, jours ouvres uniquement
        for (int offset = -7; offset <= 21; offset++) {
            LocalDate jour = LocalDate.now().plusDays(offset);
            if (offset == 0 || jour.getDayOfWeek() == DayOfWeek.SATURDAY || jour.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            for (Salle salle : salles) {
                int nb = random.nextInt(3); // 0 a 2 creneaux par salle et par jour
                for (int i = 0; i < nb; i++) {
                    LocalDateTime debut = jour.atTime(8 + random.nextInt(9), random.nextBoolean() ? 0 : 30);
                    LocalDateTime fin = debut.plusHours(1 + random.nextInt(3));
                    if (estLibre(planifiees, salle, debut, fin)) {
                        Utilisateur u = utilisateurs.get(random.nextInt(utilisateurs.size()));
                        int participants = 1 + random.nextInt(salle.getCapacite());
                        planifiees.add(new Reservation(u, salle, debut, fin, participants,
                                MOTIFS[random.nextInt(MOTIFS.length)]));
                    }
                }
            }
        }

        // Environ 1 reservation future sur 10 est annulee
        for (int i = 1; i < planifiees.size(); i++) {
            Reservation r = planifiees.get(i);
            if (r.getDateDebut().isAfter(LocalDateTime.now()) && random.nextInt(10) == 0) {
                r.setStatut(StatutReservation.ANNULEE);
            }
        }

        for (Reservation r : planifiees) {
            em.persist(r);
        }
        return planifiees.size();
    }

    private boolean estLibre(List<Reservation> existantes, Salle salle, LocalDateTime debut, LocalDateTime fin) {
        return existantes.stream()
                .filter(r -> r.getSalle() == salle)
                .noneMatch(r -> r.chevauche(debut, fin));
    }
}
