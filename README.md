# Réservation de salles — JPA / Hibernate (TP Capstone)

Application Java SE / JEE « pure » (sans Spring) : JPA 2.2 (`javax.persistence`), Hibernate 5.6,
cache de second niveau Ehcache 3 (JCache), migrations SQL via JDBC, tests JUnit 5.

## Lancer

```bash
mvn test                 # 43 tests fonctionnels
mvn compile exec:java    # application complète (MainApp)
```

Par défaut la base est **H2 en mémoire** (mode MySQL) : rien à installer.
Pour **MySQL** :

```bash
mvn compile exec:java -Ddb.url="jdbc:mysql://localhost:3306/reservation?createDatabaseIfNotExist=true" -Ddb.user=root -Ddb.password=password
```

Pour voir le SQL : `-Dhibernate.show_sql=true` (toute propriété `hibernate.*` est transmise).

## Correspondance avec les étapes du TP

| Étape | Où |
|---|---|
| 1. Modèle de données | `model/` : `Utilisateur`, `Salle`, `Equipement` (ManyToMany), `Reservation`, `StatutReservation`. `@Version`, `FetchType.LAZY`, contraintes `nullable`/`unique`, contrôle `@PrePersist` |
| 2. Cache L2 | `persistence.xml` + `ehcache.xml`. `Salle`/`Utilisateur` en READ_WRITE, `Equipement` en READ_ONLY (`@Immutable`), `Reservation` volontairement non cachée. Cache de requêtes via le hint `org.hibernate.cacheable` |
| 3. Jeu de données | `util/DataInitializer` : 12 utilisateurs, 8 salles, 6 équipements, ~150 réservations (dates relatives à aujourd'hui, graine fixe, idempotent) |
| 4. Tests | `src/test/java/com/example/test/` : `ReservationTest`, `CacheTest`, `QueryTest`, `MigrationExecutorTest`, `DataInitializerEtRapportTest` |
| 5. Script de migration | `resources/db/migration/v1_0_init_schema.sql`, `v2_0_add_description.sql` |
| 6. MigrationExecutor | `util/MigrationExecutor` : découpage des instructions, transaction par script, table `schema_version` (ré-exécution sans erreur) |
| 7. Rapport de performance | `util/PerformanceReport` : console + `target/performance-report.txt` |
| 8. Classe principale | `MainApp` : migration → données → démos cache / requêtes / service → rapport |
| 9. JPQL & Criteria | `repository/ReservationRepository`, `SalleRepository` (JOIN FETCH, sous-requête NOT EXISTS, filtre dynamique, projection DTO) |
| 10. Transactions | `util/TransactionHelper` (begin/commit/rollback), `service/ReservationService` (`OptimisticLockException` → `ConcurrenceException`) |
| 11. Logs & erreurs | `java.util.logging` (`logging.properties`, console + `logs/reservation-app.*.log`), exceptions métier `ReservationException` / `ConflitReservationException` |

## Choix de conception

- **Le schéma est géré par les migrations**, pas par Hibernate : `hibernate.hbm2ddl.auto=validate`.
  Les migrations s'exécutent donc *avant* la création de l'`EntityManagerFactory` ; si une migration manque
  (par ex. la colonne `description` de la v2.0), Hibernate refuse de démarrer.
- **Double réservation impossible même en concurrence** : lors de la création ou du déplacement d'une réservation,
  la salle est chargée en `LockModeType.OPTIMISTIC_FORCE_INCREMENT`. Deux transactions simultanées sur la même salle
  incrémentent la même version : la seconde échoue au commit (cf. test `reservationsSimultaneesSurLaMemeSalle`).
- Une réservation **annulée** libère son créneau mais reste en base (historique).
- Hibernate 5.6 tourne sur JDK 11 → 23 : la version de Byte Buddy est forcée dans le `pom.xml` pour les JDK récents.
