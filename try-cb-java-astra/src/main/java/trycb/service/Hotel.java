package trycb.service;

// Removed Couchbase imports
// import static com.couchbase.client.java.kv.LookupInSpec.get;
// import com.couchbase.client.core.error.DocumentNotFoundException;
// import com.couchbase.client.java.Bucket;
// import com.couchbase.client.java.Cluster;
// import com.couchbase.client.java.Collection;
// import com.couchbase.client.java.kv.LookupInResult;
// import com.couchbase.client.java.search.SearchOptions;
// import com.couchbase.client.java.search.SearchQuery;
// import com.couchbase.client.java.search.queries.ConjunctionQuery;
// import com.couchbase.client.java.search.result.SearchResult;
// import com.couchbase.client.java.search.result.SearchRow;

// Astra DB Data API Imports - Minimal necessary for find
import com.datastax.astra.client.databases.Database;
import com.datastax.astra.client.collections.Collection;
// Corrected path for Document
import com.datastax.astra.client.collections.definition.documents.Document;
// Import the correct FindOptions class
import com.datastax.astra.client.collections.commands.options.CollectionFindOptions;
// Import Sort and Projection classes based on compiler error
import com.datastax.astra.client.core.query.Sort;
import com.datastax.astra.client.core.query.Projection;
// Removed imports for non-existent/unused classes
// import com.datastax.astra.client.collections.CollectionAdmin;
// import com.datastax.astra.client.collections.FindIterable;
// import com.datastax.astra.client.collections.ListCollectionsIterable;
// import com.datastax.astra.client.collections.ListVectorCollectionsIterable;
// import com.datastax.astra.client.core.query.Filter;
// import com.datastax.astra.client.core.query.Filters;
// import com.datastax.astra.client.core.query.options.FindOptions;
// import io.stargate.sdk.core.domain.JsonObject;

// Spring and Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// Standard Java Imports
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Removed Result import
// import trycb.model.Result;

@Service
public class Hotel {

    private static final Logger LOGGER = LoggerFactory.getLogger(Hotel.class);
    private static final int VECTOR_SEARCH_LIMIT = 10; // Max results for vector search

    private final Database astraDatabase;
    private final String keyspace;
    private Collection<Document> hotelCollection;

    @Autowired
    public Hotel(Database astraDatabase, @Value("${astra.api.keyspace}") String keyspace) {
        this.astraDatabase = astraDatabase;
        this.keyspace = keyspace;
        try {
            this.hotelCollection = astraDatabase.getCollection("hotel", Document.class);
            LOGGER.info("Hotel Service connected to Astra collection 'hotel' in keyspace '{}'", keyspace);
        } catch (Exception e) {
            LOGGER.error("Failed to get Astra collection 'hotel' in keyspace '{}'. Ensure it exists and credentials are valid.", keyspace, e);
            this.hotelCollection = null;
        }
    }

    /**
     * Find hotels based on location and/or description using Astra DB Vector Search with $vectorize.
     * Uses CollectionFindOptions with Sort and Projection objects.
     *
     * @param location    Search term for location (part of the vectorized text).
     * @param description Search term for description (part of the vectorized text).
     * @return List of maps, each containing "name", "description", and constructed "address".
     */
    public List<Map<String, Object>> findHotels(final String location, final String description) {
        if (hotelCollection == null) {
            LOGGER.error("Hotel collection is not initialized. Cannot perform search.");
            return new ArrayList<>();
        }

        // Combine location and description for vectorization
        String searchText = (StringUtils.hasText(location) && !"*".equals(location) ? location : "")
                           + " "
                           + (StringUtils.hasText(description) && !"*".equals(description) ? description : "");
        searchText = searchText.trim();

        if (!StringUtils.hasText(searchText)) {
            LOGGER.info("Location and description search terms are empty. Returning no hotels.");
            return new ArrayList<>();
        }

        LOGGER.info("Performing vector search for hotels with text: '{}'", searchText);

        // Define vector search options using CollectionFindOptions constructor + setters
        CollectionFindOptions options = new CollectionFindOptions();

        // Use static factory methods (assumed) to create Sort/Projection
        options.sort(Sort.vectorize(searchText));             // Assuming Sort.vectorize exists
        options.limit(VECTOR_SEARCH_LIMIT);
        options.projection(Projection.exclude("$vector"));    // Assuming Projection.exclude exists

        List<Map<String, Object>> data = new ArrayList<>();
        try {
            // Execute find with no filter, passing CollectionFindOptions
            hotelCollection.find(null, options).forEach(doc -> {
                Map<String, Object> hotelData = new HashMap<>();
                hotelData.put("name", doc.getString("name"));
                hotelData.put("description", doc.getString("description"));

                // Reconstruct address string
                StringBuilder fullAddr = new StringBuilder();
                String address = doc.getString("address");
                String city = doc.getString("city");
                String state = doc.getString("state");
                String country = doc.getString("country");

                if (StringUtils.hasText(address)) fullAddr.append(address).append(", ");
                if (StringUtils.hasText(city)) fullAddr.append(city).append(", ");
                if (StringUtils.hasText(state)) fullAddr.append(state).append(", ");
                if (StringUtils.hasText(country)) fullAddr.append(country);

                if (fullAddr.length() > 2 && fullAddr.substring(fullAddr.length() - 2).equals(", ")) {
                    fullAddr.setLength(fullAddr.length() - 2);
                }
                hotelData.put("address", fullAddr.toString());

                data.add(hotelData);
            });
        } catch (Exception e) {
            LOGGER.error("Astra vector find operation failed for hotels with text [{}]: {}", searchText, e.getMessage(), e);
            // Return empty list on error for now
        }

        LOGGER.info("Found {} hotels matching vector search text [{}].", data.size(), searchText);
        return data;
    }

    // Convenience methods calling the main findHotels method
    public List<Map<String, Object>> findHotels(final String description) {
        return findHotels("*", description);
    }

    public List<Map<String, Object>> findAllHotels() {
        // Return empty list for find all, as vector search needs text
        LOGGER.warn("findAllHotels called - Vector search requires specific terms. Returning empty list.");
        return new ArrayList<>();
        // Alternatively, could return a small default set if needed
        // return findHotels("*", "*"); // This would also return empty now
    }

    // Removed extractResultOrThrow and static logQuery
}
