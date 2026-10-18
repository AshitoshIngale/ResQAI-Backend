package com.resqai.model;

/**
 * Minimal read view shared by the Phase 2 embedded contact seed and the
 * Phase 3 dedicated contact collection, so /users/me can serve either.
 */
public interface ContactView {
    String getName();
    String getPhone();
    String getRelationship();
}
