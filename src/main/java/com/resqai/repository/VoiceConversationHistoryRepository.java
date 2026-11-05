package com.resqai.repository;

import com.resqai.model.VoiceConversationHistoryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface VoiceConversationHistoryRepository
        extends MongoRepository<VoiceConversationHistoryDocument, String> {
}
