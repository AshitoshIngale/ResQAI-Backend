package com.resqai.config;

import com.resqai.model.EmergencyContactDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.UserDocument;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.repository.EmergencyProfileRepository;

import com.resqai.model.IncidentDocument;
import com.resqai.model.PendingVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time (idempotent) migration: contacts embedded inside emergency profile
 * documents (Phase 2 shape) are copied into the dedicated emergency_contacts
 * collection with generated stable ids, then the embedded list is cleared.
 * Matching is by owner + name + phone so re-running never duplicates.
 */
@Configuration
public class DataMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository contacts;

    public DataMigrationRunner(org.springframework.data.mongodb.core.MongoTemplate mongoTemplate,
                               EmergencyProfileRepository profiles,
                               EmergencyContactRepository contacts) {
        this.mongoTemplate = mongoTemplate;
        this.profiles = profiles;
        this.contacts = contacts;
    }

    @Bean
    ApplicationRunner migrateAndEnsureIndexes() {
        return args -> {
            try {
                ensureIndexes();
                migrate();
            } catch (Exception e) {
                // MongoDB unreachable at startup must NOT prevent the backend from
                // starting: health reports DOWN, endpoints fail until Mongo returns,
                // and index creation + migration re-run on the next restart.
                log.warn("Skipping startup data migration/index creation (MongoDB unreachable): {}",
                    e.getClass().getSimpleName());
            }
        };
    }

    /**
     * Explicit index creation replaces eager spring.data.mongodb.auto-index-creation,
     * whose first DB access aborts the whole Spring context when MongoDB is down.
     */
    private void ensureIndexes() {
        org.springframework.data.mongodb.core.index.IndexOperations usersIdx =
            mongoTemplate.indexOps(UserDocument.class);
        usersIdx.ensureIndex(new org.springframework.data.mongodb.core.index.Index()
            .on("normalizedEmail", org.springframework.data.domain.Sort.Direction.ASC)
            .unique().named("uniq_email"));
        usersIdx.ensureIndex(new org.springframework.data.mongodb.core.index.Index()
            .on("normalizedPhone", org.springframework.data.domain.Sort.Direction.ASC)
            .unique().sparse().named("uniq_phone"));
        usersIdx.ensureIndex(new org.springframework.data.mongodb.core.index.Index()
            .on("emergencyCode", org.springframework.data.domain.Sort.Direction.ASC)
            .unique().sparse().named("uniq_emergency_code"));

        mongoTemplate.indexOps(EmergencyProfileDocument.class).ensureIndex(
            new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
                new org.bson.Document("userId", 1)).unique().named("uniq_user"));

        org.springframework.data.mongodb.core.index.IndexOperations contactsIdx =
            mongoTemplate.indexOps(EmergencyContactDocument.class);
        contactsIdx.ensureIndex(new org.springframework.data.mongodb.core.index.Index()
            .on("contactId", org.springframework.data.domain.Sort.Direction.ASC)
            .unique().named("uniq_contact_id"));
        contactsIdx.ensureIndex(new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
            new org.bson.Document("ownerUserId", 1)).named("idx_owner"));

        org.springframework.data.mongodb.core.index.IndexOperations incidentIdx =
            mongoTemplate.indexOps(IncidentDocument.class);
        incidentIdx.ensureIndex(new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
                new org.bson.Document("ownerUserId", 1).append("incidentId", 1))
            .unique().named("uniq_owner_incident"));
        incidentIdx.ensureIndex(new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
                new org.bson.Document("ownerUserId", 1).append("clientCreatedAt", -1))
            .named("idx_owner_created"));
        incidentIdx.ensureIndex(new org.springframework.data.mongodb.core.index.Index()
            .on("idempotencyKey", org.springframework.data.domain.Sort.Direction.ASC)
            .named("idx_idempotency_key"));

        mongoTemplate.indexOps(PendingVerification.class).ensureIndex(
            new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(
                new org.bson.Document("kind", 1).append("key", 1)).named("idx_kind_key"));

        log.info("MongoDB indexes ensured");
    }

    private void migrate() {
        {
            List<EmergencyProfileDocument> withEmbedded = new ArrayList<>();
            profiles.findAll().forEach(profile -> {
                if (profile.getEmergencyContacts() != null && !profile.getEmergencyContacts().isEmpty()) {
                    withEmbedded.add(profile);
                }
            });
            if (withEmbedded.isEmpty()) return;

            int migrated = 0;
            for (EmergencyProfileDocument profile : withEmbedded) {
                for (EmergencyProfileDocument.Contact embedded : profile.getEmergencyContacts()) {
                    boolean alreadyExists = contacts.findByOwnerUserIdOrderByCreatedAtAsc(profile.getUserId())
                        .stream()
                        .anyMatch(c -> c.getName() != null && c.getName().equals(embedded.getName())
                            && c.getPhone() != null && c.getPhone().equals(embedded.getPhone()));
                    if (alreadyExists) continue;

                    contacts.save(new EmergencyContactDocument(
                        java.util.UUID.randomUUID().toString(),
                        profile.getUserId(),
                        embedded.getName(),
                        embedded.getPhone(),
                        embedded.getRelationship(),
                        false));
                    migrated++;
                }
                profile.getEmergencyContacts().clear();
                profiles.save(profile);
            }
            if (migrated > 0) {
                log.info("Migrated {} embedded contacts into emergency_contacts collection", migrated);
            }
        };
    }
}
