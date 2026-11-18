-- =====================================================================
-- Migration version 2.0
-- Compatible MySQL 8 et H2 (mode MySQL).
-- Remarque : sous MySQL, chaque instruction DDL provoque un COMMIT implicite ;
-- sous H2 le script entier est execute dans une seule transaction.
-- =====================================================================

-- Ajout de la colonne "description" a la table "salles"
ALTER TABLE salles ADD COLUMN description VARCHAR(255);

-- Ajout d'un index unique sur la colonne "email" de la table "utilisateurs"
CREATE UNIQUE INDEX idx_utilisateur_email ON utilisateurs (email);

-- Index composite pour accelerer la detection des conflits de reservation
-- (WHERE salle_id = ? AND date_debut < ? AND date_fin > ?)
CREATE INDEX idx_reservation_salle_periode ON reservations (salle_id, date_debut, date_fin);
