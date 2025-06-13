package it.gov.pagopa.payments.consumers.cache.model;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheUpdateEvent {
    // todo add docs
    private String cacheVersion;
    private String version;
    private String timestamp;

}
