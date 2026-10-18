package com.resqai.config;

import com.resqai.model.EmergencyContactDocument;
import com.resqai.model.EmergencyProfileDocument;
import com.resqai.model.IncidentDocument;
import com.resqai.model.PendingVerification;
import com.resqai.model.UserDocument;
import com.resqai.repository.EmergencyContactRepository;
import com.resqai.repository.EmergencyProfileRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup data migration + index creation, resilient to MongoDB outages.
 *
 * Runs on a background daemon thread: if MongoDB is unreachable when the
 * application starts (e.g. Render egress IP not yet whitelisted in Atlas),
 * startup is NOT blocked and the work retries every 60 seconds until it
 * succeeds — so a later Atlas Network Access fix takes effect WITHOUT a
 * redeploy. A skipped migration is never treated as successful.
 */
@Component
public class DataMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);
    private static final long RETRY_INTERVAL_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 120; // ~2 hours of retries

    private final MongoTemplate mongoTemplate;
    private final EmergencyProfileRepository profiles;
    private final EmergencyContactRepository contacts;
    private final AtomicBoolean done = new AtomicBoolean(false);
    private ScheduledExecutorService retryScheduler;

    public DataMigrationRunner(MongoTemplate mongoTemplate,
                               EmergencyProfileRepository profiles,
                               EmergencyContactRepository contacts) {
        this.mongoTemplate = mongoTemplate;
        this.profiles = profiles;
        this.contacts = contacts;
    }

    @PostConstruct
    public void startBackgroundMigration() {
        Thread worker = new Thread(this::runWithRetry, "resqai-db-migration");
        worker.setDaemon(true);
        worker.start();
    }

    private void runWithRetry() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ensureIndexes();
                migrateEmbeddedContacts();
                done.set(true);
                log.info("MongoDB migration and index creation completed (attempt {})", attempt);
                return;
            } catch (Exception e) {
                if (attempt == 1) {
                    log.warn("MongoDB unreachable at startup - migration/index creation will retry "
                        + "every {}s. If this persists, add this service's outbound IP to "
                        + "MongoDB Atlas -> Network Access. Cause: {}",
                        RETRY_INTERVAL_SECONDS, e.getClass().getSimpleName());
                }
            }
            try {
                Thread.sleep(RETRY_INTERVAL_SECONDS * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.error("MongoDB migration gave up after {} attempts. Restart the service after "
            + "fixing MongoDB connectivity.", MAX_ATTEMPTS);
    }

    /** Explicit index creation replaces eager auto-index-creation. */
    private void ensureIndexes() {
        IndexOperations usersIdx = mongoTemplate.indexOps(UserDocument.class);
        usersIdx.ensureIndex(new Index()
            .on("normalizedEmail", Sort.Direction.ASC).unique().named("uniq_email"));
        usersIdx.ensureIndex(new Index()
            .on("normalizedPhone", Sort.Direction.ASC).unique().sparse().named("uniq_phone"));
        usersIdx.ensureIndex(new Index()
            .on("emergencyCode", Sort.Direction.ASC).unique().sparse().named("uniq_emergency_code"));

        mongoTemplate.indexOps(EmergencyProfileDocument.class).ensureIndex(
            new CompoundIndexDefinition(new Document("userId", 1)).unique().named("uniq_user"));

        IndexOperations contactsIdx = mongoTemplate.indexOps(EmergencyContactDocument.class);
        contactsIdx.ensureIndex(new Index()
            .on("contactId", Sort.Direction.ASC).unique().named("uniq_contact_id"));
        contactsIdx.ensureIndex(new CompoundIndexDefinition(
            new Document("ownerUserId", 1)).named("idx_owner"));

        IndexOperations incidentIdx = mongoTemplate.indexOps(IncidentDocument.class);
        incidentIdx.ensureIndex(new CompoundIndexDefinition(
                new Document("ownerUserId", 1).append("incidentId", 1))
            .unique().named("uniq_owner_incident"));
        incidentIdx.ensureIndex(new CompoundIndexDefinition(
                new Document("ownerUserId", 1).append("clientCreatedAt", -1))
            .named("idx_owner_created"));
        incidentIdx.ensureIndex(new Index()
            .on("idempotencyKey", Sort.Direction.ASC).named("idx_idempotency_key"));

        mongoTemplate.indexOps(PendingVerification.class).ensureIndex(
            new CompoundIndexDefinition(new Document("kind", 1).append("key", 1))
                .named("idx_kind_key"));

        log.info("MongoDB indexes ensured");
    }

    /**
     * One-time (idempotent) migration: contacts embedded inside emergency
     * profile documents (Phase 2 shape) are copied into the dedicated
     * emergency_contacts collection with generated stable ids. Matching is by
     * owner + name + phone so re-running never duplicates.
     */
    private void migrateEmbeddedContacts() {
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
    }
}
