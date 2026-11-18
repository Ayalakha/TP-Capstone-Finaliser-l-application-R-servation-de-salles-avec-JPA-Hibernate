package com.example.test;

import com.example.model.Equipement;
import com.example.model.Salle;
import com.example.repository.SalleRepository;
import com.example.util.TransactionHelper;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Verifie le comportement du cache de second niveau et du cache de requetes. */
public class CacheTest {

    private static final String BASE = "cache_test";

    private static EntityManagerFactory emf;
    private static Statistics stats;
    private static Long salleId;

    @BeforeAll
    public static void init() throws Exception {
        emf = TestSupport.creerBase(BASE, true);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        salleId = avecEm(em -> em.createQuery("SELECT MIN(s.id) FROM Salle s", Long.class).getSingleResult());
    }

    @AfterAll
    public static void close() {
        TestSupport.detruireBase(emf, BASE);
    }

    @BeforeEach
    public void reset() {
        emf.getCache().evictAll(); // entites et collections uniquement
        emf.unwrap(SessionFactory.class).getCache().evictQueryRegions(); // resultats de requetes
        stats.clear();
    }

    @Test
    public void deuxiemeLectureDepuisLeCacheL2() {
        executer(em -> em.find(Salle.class, salleId));
        assertEquals(1, stats.getSecondLevelCacheMissCount());
        assertEquals(1, stats.getPrepareStatementCount());
        assertTrue(emf.getCache().contains(Salle.class, salleId));

        // Nouvel EntityManager : le cache de 1er niveau est vide, mais le cache L2 est partage
        executer(em -> em.find(Salle.class, salleId));
        assertEquals(1, stats.getSecondLevelCacheHitCount());
        assertEquals(1, stats.getPrepareStatementCount(), "aucune requete SQL supplementaire");
    }

    @Test
    public void cachePremierNiveauSansAccesAuCacheL2() {
        executer(em -> {
            Salle a = em.find(Salle.class, salleId);
            Salle b = em.find(Salle.class, salleId);
            assertSame(a, b, "meme instance dans un meme EntityManager (cache de 1er niveau)");
        });
        assertEquals(0, stats.getSecondLevelCacheHitCount());
        assertEquals(1, stats.getPrepareStatementCount());
    }

    @Test
    public void evictionForceLeRechargement() {
        executer(em -> em.find(Salle.class, salleId));
        emf.getCache().evict(Salle.class, salleId);
        assertFalse(emf.getCache().contains(Salle.class, salleId));

        executer(em -> em.find(Salle.class, salleId));
        assertEquals(2, stats.getPrepareStatementCount());
    }

    @Test
    public void miseAJourVisibleDansLeCache() {
        executer(em -> em.find(Salle.class, salleId));
        TransactionHelper.inTransaction(emf, em -> {
            em.find(Salle.class, salleId).setDescription("Description modifiee");
            return null;
        });
        stats.clear();

        String description = avecEm(em -> em.find(Salle.class, salleId).getDescription());
        assertEquals("Description modifiee", description, "READ_WRITE : le cache est mis a jour au commit");
        assertEquals(1, stats.getSecondLevelCacheHitCount());
        assertEquals(0, stats.getPrepareStatementCount());
    }

    @Test
    public void cacheDeCollectionEtEntiteReadOnly() {
        executer(em -> em.find(Salle.class, salleId).getEquipements().size());
        stats.clear();

        int nb = avecEm(em -> em.find(Salle.class, salleId).getEquipements().size());
        assertTrue(nb > 0);
        assertEquals(0, stats.getPrepareStatementCount(), "salle, collection et equipements viennent du cache");
        assertTrue(stats.getCacheRegionStatistics(Equipement.class.getName()).getHitCount() > 0);
    }

    @Test
    public void cacheDeRequetes() {
        executer(em -> new SalleRepository(em).findAll());
        assertEquals(1, stats.getQueryCacheMissCount());
        assertEquals(1, stats.getQueryCachePutCount());

        executer(em -> new SalleRepository(em).findAll());
        assertEquals(1, stats.getQueryCacheHitCount());
        assertEquals(1, stats.getQueryExecutionCount(), "la 2e requete n'est pas executee en base");
    }

    @Test
    public void cacheDeRequetesInvalideApresModification() {
        executer(em -> new SalleRepository(em).findAll());
        TransactionHelper.inTransaction(emf, em -> {
            em.persist(new Salle("Salle Temporaire", 4, "Batiment Z", 0, null));
            return null;
        });
        int nb = avecEm(em -> new SalleRepository(em).findAll().size());
        assertEquals(9, nb, "la table salles a change : le resultat en cache est perime et la requete est rejouee");
        assertEquals(2, stats.getQueryExecutionCount());

        TransactionHelper.inTransaction(emf, em -> {
            em.remove(new SalleRepository(em).findByNom("Salle Temporaire").orElseThrow());
            return null;
        });
    }

    private static void executer(Consumer<EntityManager> action) {
        avecEm(em -> {
            action.accept(em);
            return null;
        });
    }

    private static <T> T avecEm(java.util.function.Function<EntityManager, T> action) {
        EntityManager em = emf.createEntityManager();
        try {
            return action.apply(em);
        } finally {
            em.close();
        }
    }
}
