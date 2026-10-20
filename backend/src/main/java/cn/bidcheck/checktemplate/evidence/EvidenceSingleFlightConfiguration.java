package cn.bidcheck.checktemplate.evidence;

import cn.bidcheck.persistence.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Production wiring for durable evidence single-flight coordination. */
@Configuration(proxyBeanMethods = false)
public class EvidenceSingleFlightConfiguration {
    @Bean
    public EvidenceTaskRepository evidenceTaskRepository(Store store, ObjectMapper json,
                                                         PlatformTransactionManager manager) {
        return new StoreEvidenceTaskRepository(store, json, manager);
    }

    @Bean(destroyMethod = "close")
    public EvidenceNeedCoordinator evidenceNeedCoordinator(EvidenceTaskRepository repository) {
        return new EvidenceNeedCoordinator(repository);
    }
}
