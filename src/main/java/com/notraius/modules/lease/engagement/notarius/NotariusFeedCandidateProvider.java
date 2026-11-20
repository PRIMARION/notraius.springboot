package com.notraius.modules.lease.engagement.notarius;

import com.notraius.database.repositories.asset.asset.PropertyRepository;
import org.primarion.module.app.engagement.impression.feed.feed.FeedCandidate;
import org.primarion.module.app.engagement.impression.feed.service.FeedCandidateProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import com.notraius.modules.lease.engagement.NotariusEngagementReferences;

/**
 * The notarius feed pool: published properties, newest first.
 * <p>
 * The module does the ranking; this seam only says which rows are eligible at
 * all. Publication is the eligibility rule here, so a draft, a paused or a
 * suspended property never enters the pool. LEASED is deliberately excluded as
 * well — it is engageable row-level (see {@code NotariusEngagementReferences}), but
 * a taken home is never <i>recommended</i>. The cap comes from
 * {@code app.engagement.candidate-pool}.
 */
@Component
public class NotariusFeedCandidateProvider implements FeedCandidateProvider {

    private static final String PROPERTY = "PROPERTY";
    private static final String PUBLISHED = "PUBLISHED";
    private static final int MAX_LIMIT = 1000;

    private final PropertyRepository properties;

    public NotariusFeedCandidateProvider(PropertyRepository properties) {
        this.properties = properties;
    }

    @Override
    public List<FeedCandidate> candidates(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return properties.search(null, PUBLISHED, null, null, null,
                        PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(property -> new FeedCandidate(PROPERTY, property.getId(),
                        property.getCreatedAt()))
                .toList();
    }
}
