-- =====================================================================
-- Migration version 1.0 : creation du schema initial
-- Compatible MySQL 8 et H2 (mode MySQL).
-- =====================================================================

-- Utilisateurs de l'application
CREATE TABLE utilisateurs (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    email       VARCHAR(150) NOT NULL,
    nom         VARCHAR(100) NOT NULL,
    departement VARCHAR(100),
    version     INT          NOT NULL DEFAULT 0
);

-- Salles reservables
CREATE TABLE salles (
    id       BIGINT       AUTO_INCREMENT PRIMARY KEY,
    nom      VARCHAR(100) NOT NULL,
    capacite INT          NOT NULL,
    batiment VARCHAR(50),
    etage    INT,
    version  INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_salle_nom UNIQUE (nom),
    CONSTRAINT ck_salle_capacite CHECK (capacite > 0)
);

-- Equipements (donnees de reference immuables)
CREATE TABLE equipements (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    nom         VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uk_equipement_nom UNIQUE (nom)
);

-- Association ManyToMany salle <-> equipement
CREATE TABLE salle_equipements (
    salle_id      BIGINT NOT NULL,
    equipement_id BIGINT NOT NULL,
    PRIMARY KEY (salle_id, equipement_id),
    CONSTRAINT fk_se_salle FOREIGN KEY (salle_id) REFERENCES salles (id),
    CONSTRAINT fk_se_equipement FOREIGN KEY (equipement_id) REFERENCES equipements (id)
);

-- Reservations : une salle + un utilisateur + un creneau
CREATE TABLE reservations (
    id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    salle_id            BIGINT       NOT NULL,
    utilisateur_id      BIGINT       NOT NULL,
    date_debut          DATETIME     NOT NULL,
    date_fin            DATETIME     NOT NULL,
    nombre_participants INT          NOT NULL DEFAULT 1,
    motif               VARCHAR(255),
    statut              VARCHAR(20)  NOT NULL DEFAULT 'CONFIRMEE',
    version             INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservation_salle FOREIGN KEY (salle_id) REFERENCES salles (id),
    CONSTRAINT fk_reservation_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateurs (id),
    CONSTRAINT ck_reservation_dates CHECK (date_fin > date_debut)
);

CREATE INDEX idx_reservation_utilisateur ON reservations (utilisateur_id);
