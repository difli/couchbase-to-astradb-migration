package trycb.config;

import com.datastax.astra.client.DataAPIClient;
import com.datastax.astra.client.databases.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class AstraConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AstraConfig.class);

    @Value("${astra.api.endpoint}")
    private String astraEndpoint;

    @Value("${astra.api.token}")
    private String astraToken;

    // Optional: Keyspace can be injected here or directly in services
    // @Value("${astra.api.keyspace}")
    // private String astraKeyspace;

    @Bean
    public DataAPIClient dataAPIClient() {
        if (!StringUtils.hasText(astraEndpoint) || !StringUtils.hasText(astraToken)) {
            LOGGER.error("Astra DB endpoint or token is missing in application properties. Cannot initialize DataAPIClient.");
            throw new IllegalStateException("Astra DB endpoint and token properties (astra.api.endpoint, astra.api.token) are required.");
        }
        LOGGER.info("Initializing DataAPIClient for endpoint: {}", astraEndpoint);

        // Clean the token: remove surrounding whitespace and potential quotes
        String cleanedToken = astraToken.trim();
        if (cleanedToken.startsWith("\"") && cleanedToken.endsWith("\"")) {
            cleanedToken = cleanedToken.substring(1, cleanedToken.length() - 1);
        }
        
        return new DataAPIClient(cleanedToken);
    }

    @Bean
    public Database astraDatabase(DataAPIClient dataApiClient, @Value("${astra.api.keyspace}") String keyspace) {
        if (dataApiClient == null || !StringUtils.hasText(keyspace)) {
             LOGGER.error("DataAPIClient bean not available or keyspace is missing. Cannot initialize Database bean.");
             throw new IllegalStateException("DataAPIClient bean and keyspace property (astra.api.keyspace) are required.");
        }
        LOGGER.info("Initializing Database bean for keyspace: {}", keyspace);
        // The getDatabase method likely uses the endpoint from the client
        return dataApiClient.getDatabase(astraEndpoint, keyspace);
    }

} 