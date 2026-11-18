package com.example.util;

import org.hibernate.SessionFactory;
import org.hibernate.stat.CacheRegionStatistics;
import org.hibernate.stat.QueryStatistics;
import org.hibernate.stat.Statistics;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Rapport de performance base sur les statistiques Hibernate
 * (hibernate.generate_statistics=true dans persistence.xml).
 */
public class PerformanceReport {

    private static final Logger LOGGER = Logger.getLogger(PerformanceReport.class.getName());
    private static final int TOP_REQUETES = 5;

    private final EntityManagerFactory emf;

    public PerformanceReport(EntityManager em) {
        this(em.getEntityManagerFactory());
    }

    public PerformanceReport(EntityManagerFactory emf) {
        this.emf = emf;
    }

    public Statistics getStatistics() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    public void printReport() {
        System.out.println(generateReport());
    }

    /** Ecrit le rapport dans un fichier texte (les dossiers parents sont crees si besoin). */
    public void exportReport(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, generateReport().getBytes(StandardCharsets.UTF_8));
        LOGGER.info("Rapport de performance exporte : " + file.toAbsolutePath());
    }

    public String generateReport() {
        Statistics stats = getStatistics();
        StringBuilder sb = new StringBuilder();
        sb.append("==================== RAPPORT DE PERFORMANCE ====================\n");
        sb.append("Genere le ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))).append('\n');

        section(sb, "Sessions et transactions");
        line(sb, "Sessions ouvertes / fermees", stats.getSessionOpenCount() + " / " + stats.getSessionCloseCount());
        line(sb, "Transactions", stats.getTransactionCount() + " (dont " + stats.getSuccessfulTransactionCount() + " reussies)");
        line(sb, "Connexions JDBC obtenues", stats.getConnectCount());
        line(sb, "Instructions SQL preparees", stats.getPrepareStatementCount());
        line(sb, "Echecs de verrouillage optimiste", stats.getOptimisticFailureCount());

        section(sb, "Requetes JPQL / Criteria");
        long tempsTotal = Arrays.stream(stats.getQueries())
                .mapToLong(q -> stats.getQueryStatistics(q).getExecutionTotalTime())
                .sum();
        line(sb, "Requetes executees", stats.getQueryExecutionCount());
        line(sb, "Duree totale des requetes (ms)", tempsTotal);
        line(sb, "Requete la plus lente (ms)", stats.getQueryExecutionMaxTime());
        if (stats.getQueryExecutionMaxTimeQueryString() != null) {
            sb.append("    -> ").append(abreger(stats.getQueryExecutionMaxTimeQueryString())).append('\n');
        }

        section(sb, "Entites");
        line(sb, "Chargees (load)", stats.getEntityLoadCount());
        line(sb, "Recuperees par SQL (fetch)", stats.getEntityFetchCount());
        line(sb, "Inserees / modifiees / supprimees",
                stats.getEntityInsertCount() + " / " + stats.getEntityUpdateCount() + " / " + stats.getEntityDeleteCount());
        line(sb, "Collections chargees / recuperees", stats.getCollectionLoadCount() + " / " + stats.getCollectionFetchCount());

        section(sb, "Cache de second niveau");
        long hits = stats.getSecondLevelCacheHitCount();
        long misses = stats.getSecondLevelCacheMissCount();
        line(sb, "Cache de second niveau hits", hits);
        line(sb, "Cache de second niveau misses", misses);
        line(sb, "Cache de second niveau mises a jour", stats.getSecondLevelCachePutCount());
        line(sb, "Taux de succes", ratio(hits, misses));
        for (String region : stats.getSecondLevelCacheRegionNames()) {
            CacheRegionStatistics rs = stats.getCacheRegionStatistics(region);
            if (rs != null) {
                sb.append(String.format("    %-45s hits=%-5d misses=%-5d puts=%-5d%n",
                        region, rs.getHitCount(), rs.getMissCount(), rs.getPutCount()));
            }
        }

        section(sb, "Cache de requetes");
        long qHits = stats.getQueryCacheHitCount();
        long qMisses = stats.getQueryCacheMissCount();
        line(sb, "Hits / misses / mises a jour", qHits + " / " + qMisses + " / " + stats.getQueryCachePutCount());
        line(sb, "Taux de succes", ratio(qHits, qMisses));

        section(sb, "Top " + TOP_REQUETES + " des requetes (temps cumule)");
        List<String> top = Arrays.stream(stats.getQueries())
                .sorted(Comparator.comparingLong((String q) -> stats.getQueryStatistics(q).getExecutionTotalTime()).reversed())
                .limit(TOP_REQUETES)
                .collect(Collectors.toList());
        for (String q : top) {
            QueryStatistics qs = stats.getQueryStatistics(q);
            sb.append(String.format("  [%3d exec | total %4d ms | moy %3d ms | max %3d ms | %4d lignes] %s%n",
                    qs.getExecutionCount(), qs.getExecutionTotalTime(), qs.getExecutionAvgTime(),
                    qs.getExecutionMaxTime(), qs.getExecutionRowCount(), abreger(q)));
        }

        section(sb, "Recommandations");
        for (String reco : recommandations(stats)) {
            sb.append("  - ").append(reco).append('\n');
        }
        sb.append("================================================================\n");
        return sb.toString();
    }

    private List<String> recommandations(Statistics stats) {
        List<String> recos = new java.util.ArrayList<>();
        long hits = stats.getSecondLevelCacheHitCount();
        long misses = stats.getSecondLevelCacheMissCount();
        if (hits + misses > 0 && hits * 100 / (hits + misses) < 50) {
            recos.add("Taux de succes du cache L2 < 50 % : verifier les entites @Cacheable et la duree de vie (ehcache.xml).");
        }
        if (stats.getEntityFetchCount() > stats.getQueryExecutionCount() * 5 && stats.getEntityFetchCount() > 50) {
            recos.add("Beaucoup d'entites chargees une par une (fetch) : probable probleme N+1, utiliser JOIN FETCH.");
        }
        if (stats.getQueryExecutionMaxTime() > 200) {
            recos.add("Requete lente detectee (> 200 ms) : verifier les index (cf. migration v2.0).");
        }
        if (stats.getOptimisticFailureCount() > 0) {
            recos.add(stats.getOptimisticFailureCount() + " conflit(s) de verrouillage optimiste : normal sous concurrence, "
                    + "ils doivent etre geres (message utilisateur ou nouvelle tentative).");
        }
        if (recos.isEmpty()) {
            recos.add("Aucune anomalie detectee.");
        }
        return recos;
    }

    private static void section(StringBuilder sb, String titre) {
        sb.append("\n--- ").append(titre).append(" ---\n");
    }

    private static void line(StringBuilder sb, String label, Object value) {
        sb.append(String.format("  %-38s : %s%n", label, value));
    }

    private static String ratio(long hits, long misses) {
        long total = hits + misses;
        return total == 0 ? "n/a" : String.format("%.1f %%", hits * 100.0 / total);
    }

    private static String abreger(String query) {
        String q = query.replaceAll("\\s+", " ").trim();
        return q.length() > 110 ? q.substring(0, 107) + "..." : q;
    }
}
