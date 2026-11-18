package com.example.util;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.OptimisticLockException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsule le schema transactionnel RESOURCE_LOCAL :
 * begin -> travail -> commit, et rollback systematique en cas d'erreur.
 */
public final class TransactionHelper {

    private static final Logger LOGGER = Logger.getLogger(TransactionHelper.class.getName());

    private TransactionHelper() {
    }

    /** Ouvre un EntityManager dedie, execute le travail dans une transaction puis ferme l'EntityManager. */
    public static <T> T inTransaction(EntityManagerFactory emf, Function<EntityManager, T> work) {
        EntityManager em = emf.createEntityManager();
        try {
            return inTransaction(em, work);
        } finally {
            em.close();
        }
    }

    public static <T> T inTransaction(EntityManager em, Function<EntityManager, T> work) {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.apply(em);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                try {
                    tx.rollback();
                    LOGGER.fine("Transaction annulee (rollback) suite a : " + e);
                } catch (RuntimeException rollbackError) {
                    LOGGER.log(Level.SEVERE, "Echec du rollback", rollbackError);
                    e.addSuppressed(rollbackError);
                }
            }
            throw e;
        }
    }

    public static void runInTransaction(EntityManager em, Consumer<EntityManager> work) {
        inTransaction(em, e -> {
            work.accept(e);
            return null;
        });
    }

    /**
     * Vrai si l'exception (ou l'une de ses causes) traduit un echec de verrouillage optimiste.
     * Au commit, l'OptimisticLockException arrive souvent enveloppee dans une RollbackException.
     */
    public static boolean isOptimisticLockFailure(Throwable t) {
        while (t != null) {
            if (t instanceof OptimisticLockException || t instanceof org.hibernate.StaleStateException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
