package com.gpstore.platform;

/**
 * One shop reached a row belonging to another.
 *
 * THIS IS NOT AN ERROR THE APPLICATION RECOVERS FROM. It means a code path
 * loaded a shop-owned row by id, or through an association, without checking
 * whose it was - and the check that caught it is the last one there is. It is
 * thrown rather than logged so that the request fails instead of returning
 * somebody else's order with a 200.
 *
 * WHAT THE CALLER IS TOLD IS "not found", never "belongs to another shop".
 * The id of a row you may not see is not something you should be able to
 * confirm exists, and a 403 confirms it. The ids and shop ids stay in the
 * exception for the server log and never reach a response body.
 */
public class CrossShopAccessException extends RuntimeException {

    private final transient Class<?> entityType;

    public CrossShopAccessException(Class<?> entityType, Long rowShopId, Long scopeShopId) {
        super(entityType.getSimpleName() + " belongs to shop " + rowShopId
                + " but the current scope is shop " + scopeShopId);
        this.entityType = entityType;
    }

    /**
     * A crossing caught by a rule rather than by the entity listener.
     *
     * The listener catches a row LOADED across the boundary. This one is for a
     * relationship refused across it - a rider assigned to another shop's
     * order (W4), where both rows are legitimately readable and pairing them
     * is what is wrong. Same exception on purpose: the caller is told the same
     * "not found", for the same reason.
     */
    public CrossShopAccessException(String message) {
        super(message);
        this.entityType = null;
    }

    public Class<?> getEntityType() {
        return entityType;
    }
}
