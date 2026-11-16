package com.notraius.modules.engagement;

import com.notraius.database.repositories.asset.PropertyRepository;
import org.primarion.module.engagement.feed.FeedCandidate;
import org.primarion.module.engagement.feed.FeedCandidateProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;

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
