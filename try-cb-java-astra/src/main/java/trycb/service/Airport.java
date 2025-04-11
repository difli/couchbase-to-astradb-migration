package trycb.service;

// Astra DB Data API Imports - Corrected for 2.0.0-PREVIEW3
import com.datastax.astra.client.databases.Database;
import com.datastax.astra.client.collections.Collection;
// Corrected path for Document
import com.datastax.astra.client.collections.definition.documents.Document;
// Corrected path for Filter/Filters
import com.datastax.astra.client.core.query.Filter;
import com.datastax.astra.client.core.query.Filters;
// Need Map for regex filter
import java.util.Map;
// Imports for classes that likely don't exist in this version (commented out for now)
// import com.datastax.astra.client.model.FindOptions;
// import com.datastax.astra.client.model.Projection;

// Spring and Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Standard Java Imports
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Removed Result import for now, assuming simplification
// import trycb.model.Result;

@Service
public class Airport {

    private static final Logger LOGGER = LoggerFactory.getLogger(Airport.class);

    private final Database astraDatabase;
    private final String keyspace; // Keep keyspace if needed for other operations, though getCollection might not need it if DB bean has context
    private Collection<Document> airportCollection;

    @Autowired
    public Airport(Database astraDatabase, @Value("${astra.api.keyspace}") String keyspace) {
        this.astraDatabase = astraDatabase;
        this.keyspace = keyspace; // Store keyspace name

        // Initialize collection - assuming collection name is 'airport'
        // The Database bean from AstraConfig should provide necessary context (endpoint/token)
        try {
            this.airportCollection = astraDatabase.getCollection("airport", Document.class);
            // Optionally verify collection exists or is accessible here if needed
             LOGGER.info("Airport Service connected to Astra collection 'airport' in keyspace '{}'", keyspace); // Use configured keyspace for logging
        } catch (Exception e) {
             LOGGER.error("Failed to get Astra collection 'airport' in keyspace '{}'. Ensure it exists and credentials are valid.", keyspace, e);
             // Decide how to handle this - throw exception? set collection to null?
             // For now, log error. Calls to findAll will fail if collection is null.
             this.airportCollection = null;
        }
    }

    /**
     * Find airports based on search parameters using Astra DB Data API.
     * Only returns the airport name.
     *
     * @param params Search parameter (FAA code, ICAO code, or start of airport name).
     * @return List of maps, each containing only the "airportname".
     */
    public List<Map<String, Object>> findAll(String params) {
        if (airportCollection == null) {
             LOGGER.error("Airport collection is not initialized. Cannot perform search.");
             // Or throw a specific exception like IllegalStateException
             return new LinkedList<>();
        }
        if (params == null || params.trim().isEmpty()) {
            LOGGER.warn("findAll called with null or empty params.");
            return new LinkedList<>();
        }

        Filter filter = null; // Initialize filter to null
        boolean isFaaOrIcao = false;
        boolean sameCase = (params.equals(params.toUpperCase()) || params.equals(params.toLowerCase()));
        String searchParam = params.trim(); // Use trimmed param

        if (searchParam.length() == 3 && sameCase) {
            filter = Filters.eq("faa", searchParam.toUpperCase());
            isFaaOrIcao = true;
            LOGGER.info("Searching airports with FAA filter: {}", filter);
        } else if (searchParam.length() == 4 && sameCase) {
            filter = Filters.eq("icao", searchParam.toUpperCase());
            isFaaOrIcao = true;
            LOGGER.info("Searching airports with ICAO filter: {}", filter);
        } else {
            // Name search is not supported by Data API filters
            LOGGER.warn("Airport name search ('{}') is not supported. Only 3-letter FAA or 4-letter ICAO codes are searchable.", searchParam);
            return new LinkedList<>(); // Return empty list for unsupported search types
        }

        // If filter is null (shouldn't happen with above logic, but safer), return empty
        if (filter == null) {
             return new LinkedList<>();
        }

        List<Map<String, Object>> data = new LinkedList<>();
        try {
            // Execute find operation only for FAA/ICAO filters
            airportCollection.find(filter).forEach(doc -> {
                // Extract only the projected field
                String airportName = doc.getString("airportname");
                // Use getId(String.class)
                String docId = doc.getId(String.class);
                if (airportName != null) {
                    data.add(Map.of("id", docId, "airportname", airportName));
                } else {
                    LOGGER.warn("Document {} missing airportname field", docId);
                }
            });

        } catch (Exception e) {
            // Catch specific Astra exceptions if available
            LOGGER.error("Astra find operation failed for airports with filter [{}]: {}", filter, e.getMessage(), e);
            // Depending on requirements, re-throw a custom service exception or return empty/partial list
            // For now, just return the (potentially empty) list accumulated so far
            // Consider throwing custom exception: throw new DataRetrievalFailureException("Failed to retrieve airports", e);
        }

        LOGGER.info("Found {} airports matching filter [{}].", data.size(), filter);
        return data;
    }

    // Removed static logQuery helper
}
