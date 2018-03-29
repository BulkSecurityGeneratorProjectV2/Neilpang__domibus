package eu.domibus.wss4j.common.crypto;

import eu.domibus.wss4j.common.crypto.api.DomainProvider;
import org.springframework.stereotype.Service;

/**
 * @author Cosmin Baciu
 * @since 3.3
 */
@Service
public class DefaultDomainProviderImpl implements DomainProvider {

    @Override
    public String getCurrentDomain() {
        return null;
    }
}
