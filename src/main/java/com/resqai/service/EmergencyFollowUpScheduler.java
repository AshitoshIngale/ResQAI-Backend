package com.resqai.service;

import com.resqai.config.TwilioConfigurationService;
import com.resqai.model.EmergencyFollowUpJobDocument;
import com.resqai.model.EmergencyVoiceSessionDocument;
import com.resqai.repository.EmergencyFollowUpJobRepository;
import com.resqai.repository.EmergencyVoiceSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Executes the persisted follow-up jobs (spec PART 8/9): the ~5-minute
 * situation report and the ~20-minute follow-up. Jobs live in MongoDB so a
 * backend restart never loses them; RUNNING jobs stuck past a deadline are
 * reclaimed safely. Never touches the bystander AI session state (the
 * conversation stays available past 20 minutes - spec PART 9).
 */
@Component
public class EmergencyFollowUpScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmergencyFollowUpScheduler.class);
    private static final int MAX_ATTEMPTS = 3;

    private final EmergencyFollowUpJobRepository jobs;
    private final EmergencyVoiceSessionRepository voiceSessions;
    private final EmergencySituationReportService sitrepService;
    private final TwilioConfigurationService twilioConfig;

    public EmergencyFollowUpScheduler(EmergencyFollowUpJobRepository jobs,
                                      EmergencyVoiceSessionRepository voiceSessions,
                                      EmergencySituationReportService sitrepService,
                                      TwilioConfigurationService twilioConfig) {
        this.jobs = jobs;
        this.voiceSessions = voiceSessions;
        this.sitrepService = sitrepService;
        this.twilioConfig = twilioConfig;
    }

    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        try {
            var now = Instant.now();
            var page = PageRequest.of(0, 10);
            List<EmergencyFollowUpJobDocument> due = jobs
                .findByStateAndDueAtLessThanEqualOrderByDueAtAsc(
                    EmergencyFollowUpJobDocument.STATE_PENDING, now, page);
            for (var job : due) {
                execute(job);
            }
            // Reclaim stuck RUNNING jobs (backend died mid-run).
            var stuck = jobs.findByStateAndClaimedAtBefore(
                EmergencyFollowUpJobDocument.STATE_RUNNING,
                now.minusSeconds(120), page);
            stuck.forEach(j -> { j.setState(EmergencyFollowUpJobDocument.STATE_PENDING); jobs.save(j); });
        } catch (Exception e) {
            log.warn("follow-up poll failed: {}", e.getMessage());
        }
    }

    private void execute(EmergencyFollowUpJobDocument job) {
        if (job.getAttempts() >= MAX_ATTEMPTS) {
            job.setState(EmergencyFollowUpJobDocument.STATE_FAILED);
            job.setLastError("max attempts reached");
            job.setExecutedAt(Instant.now());
            jobs.save(job);
            return;
        }
        job.setState(EmergencyFollowUpJobDocument.STATE_RUNNING);
        job.setClaimedAt(Instant.now());
        job.setAttempts(job.getAttempts() + 1);
        jobs.save(job);

        try {
            var sessionOpt = voiceSessions.findByIncidentId(job.getIncidentId());
            if (sessionOpt.isEmpty()) {
                job.setState(EmergencyFollowUpJobDocument.STATE_SKIPPED);
                job.setExecutedAt(Instant.now());
                jobs.save(job);
                return;
            }
            var session = sessionOpt.get();
            if (job.getType().equals(EmergencyFollowUpJobDocument.TYPE_FIVE_MINUTE)) {
                var report = sitrepService.generate(session,
                    "FIVE_MINUTE", session.isUrgentFlagged());
                sitrepService.relay(report, ambulanceDestination());
                sitrepService.recordTimeline(job.getIncidentId(),
                    "SYSTEM",
                    "Situation report " + report.getStatus()
                        + " relayed to the ambulance (5-minute update)", false);
            } else if (job.getType().equals(EmergencyFollowUpJobDocument.TYPE_TWENTY_MINUTE)) {
                // 20-minute follow-up: situation update + in-session prompt if
                // the AI session is still ACTIVE (never auto-terminated).
                var report = sitrepService.generate(session, "FOLLOW_UP", false);
                sitrepService.relay(report, ambulanceDestination());
                sitrepService.recordTimeline(job.getIncidentId(),
                    "SYSTEM",
                    "20-minute follow-up: " + report.getStatus(), false);
                if ("ACTIVE".equals(session.getState())) {
                    session.setPendingAgentMessage(
                        "It has been about twenty minutes. Can you give me a quick update "
                            + "on the patient's condition?");
                    voiceSessions.save(session);
                }
            }
            job.setState(EmergencyFollowUpJobDocument.STATE_DONE);
            job.setExecutedAt(Instant.now());
            jobs.save(job);
        } catch (Exception e) {
            job.setLastError(String.valueOf(e.getMessage()));
            if (job.getAttempts() >= MAX_ATTEMPTS) {
                job.setState(EmergencyFollowUpJobDocument.STATE_FAILED);
                job.setExecutedAt(Instant.now());
            } else {
                job.setState(EmergencyFollowUpJobDocument.STATE_PENDING); // retry
            }
            jobs.save(job);
        }
    }

    private String ambulanceDestination() {
        // Same configured/test destination the ambulance call uses.
        return twilioConfig.ambulanceDestination(
            System.getenv().getOrDefault("EMERGENCY_CALL_DESTINATION", "+917676941547"));
    }
}
