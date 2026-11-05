package com.resqai.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Durable replay-protection store for signed SMS nonces. */
@Document(collection = "emergency_sms_nonces")
public class EmergencySmsNonceDocument {

    @Id
    private String id;

    @Indexed(name = "uniq_sms_nonce", unique = true)
    private String nonce;

    private Instant receivedAt = Instant.now();

    public EmergencySmsNonceDocument() {}
    public EmergencySmsNonceDocument(String nonce, Instant receivedAt) {
        this.nonce = nonce;
        this.receivedAt = receivedAt;
    }

    public String getNonce() { return nonce; }
    public Instant getReceivedAt() { return receivedAt; }
}
