package trycb.service;

// Removed Couchbase imports
// import com.couchbase.client.core.error.QueryException;
// import com.couchbase.client.java.Cluster;
// import com.couchbase.client.java.json.JsonArray;
// import com.couchbase.client.java.json.JsonObject;
// import com.couchbase.client.java.query.QueryOptions;
// import com.couchbase.client.java.query.QueryResult;

// Astra DB Data API Imports - Corrected for 2.0.0-PREVIEW3
import com.datastax.astra.client.databases.Database;
import com.datastax.astra.client.collections.Collection;
// Corrected path for Document
import com.datastax.astra.client.collections.definition.documents.Document;
// Corrected path for Filter/Filters
import com.datastax.astra.client.core.query.Filter;
import com.datastax.astra.client.core.query.Filters;
// Imports for classes that likely don't exist in this version (commented out for now)
// import com.datastax.astra.client.model.FindOptions;
// import com.datastax.astra.client.model.FindOneOptions;
// import com.datastax.astra.client.model.Projection;

// Spring and Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataRetrievalFailureException; // Can keep for specific exceptions
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

// Standard Java Imports
import java.util.*;
import java.util.stream.Collectors;
import java.text.DateFormat;

// Removed Result import
// import trycb.model.Result;

@Service
public class FlightPath {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlightPath.class);

    private final Database astraDatabase;
    private final String keyspace;
    private Collection<Document> airportCollection;
    private Collection<Document> routeCollection;
    private Collection<Document> airlineCollection;
    private final Random random = new Random(); // For flight time/price simulation

    @Autowired
    public FlightPath(Database astraDatabase, @Value("${astra.api.keyspace}") String keyspace) {
        this.astraDatabase = astraDatabase;
        this.keyspace = keyspace;
        try {
            // Assuming collection names match types
            this.airportCollection = astraDatabase.getCollection("airport", Document.class);
            this.routeCollection = astraDatabase.getCollection("route", Document.class);
            this.airlineCollection = astraDatabase.getCollection("airline", Document.class);
            LOGGER.info("FlightPath Service connected to Astra collections (airport, route, airline) in keyspace '{}'", keyspace);
        } catch (Exception e) {
            LOGGER.error("Failed to get one or more Astra collections (airport, route, airline) in keyspace '{}'", keyspace, e);
            // Mark collections as null to prevent usage
            this.airportCollection = null;
            this.routeCollection = null;
            this.airlineCollection = null;
        }
    }

    /**
     * Finds flight paths between two airports on a specific day using Astra DB Data API.
     * Replicates N1QL JOIN/UNNEST logic with multiple API calls and Java processing.
     *
     * @param from     Origin airport name.
     * @param to       Destination airport name.
     * @param leave    Departure date (only day of week is used).
     * @return List of flight path details.
     */
    public List<Map<String, Object>> findAll(String from, String to, Calendar leave) {
        if (airportCollection == null || routeCollection == null || airlineCollection == null) {
            LOGGER.error("One or more required collections (airport, route, airline) are not initialized.");
            return new LinkedList<>();
        }
        if (from == null || to == null || leave == null) {
            LOGGER.warn("findAll called with null parameters.");
            return new LinkedList<>();
        }

        // 1. Find FAA codes for origin and destination airports
        String fromAirportFaa = findAirportFaa(from);
        String toAirportFaa = findAirportFaa(to);

        if (fromAirportFaa == null || toAirportFaa == null) {
            LOGGER.warn("Could not find FAA codes for origin '{}' or destination '{}'", from, to);
            return new LinkedList<>();
        }
        LOGGER.info("Found FAA codes: {} -> {}", fromAirportFaa, toAirportFaa);

        // 2. Find routes matching the FAA codes
        Filter routeFilter = Filters.and(
                Filters.eq("sourceairport", fromAirportFaa),
                Filters.eq("destinationairport", toAirportFaa)
        );
        // Project necessary fields
        // FindOptions options = FindOptions.builder()
        //         .projection(Projection.include("flight", "day", "utc", "sourceairport", "destinationairport"))
        //         .limit(1) // Limit to 1 as we only need one example route
        //         .build();

        List<Map<String, Object>> finalFlightPaths = new LinkedList<>();
        int requestedDayOfWeek = leave.get(Calendar.DAY_OF_WEEK) - 1; // Calendar Sunday=1, N1QL day=0
        LOGGER.info("Searching routes for day {} ({} -> {})", requestedDayOfWeek, fromAirportFaa, toAirportFaa);

        try {
            // Removed FindOptions from find call
            routeCollection.find(routeFilter).forEach(doc -> {
                List<?> scheduleList = doc.get("schedule", List.class); // Assume schedule is a list
                String airlineId = doc.getString("airlineid");
                String equipment = doc.getString("equipment");
                String sourceAirport = doc.getString("sourceairport"); // Already known, but good to have
                String destinationAirport = doc.getString("destinationairport"); // Already known

                if (scheduleList == null || airlineId == null) {
                    LOGGER.warn("Route document (ID: {}) missing schedule or airlineid.", doc.getId(String.class));
                    return;
                }

                for (Object scheduleObj : scheduleList) {
                    if (!(scheduleObj instanceof Map)) {
                         LOGGER.warn("Unexpected item type in schedule list for route ID {}: {}", doc.getId(String.class), scheduleObj.getClass());
                         continue;
                    }
                    Map<String, Object> scheduleMap = (Map<String, Object>) scheduleObj;
                    int day = ((Number) scheduleMap.getOrDefault("day", -1)).intValue();
                    String utc = (String) scheduleMap.get("utc");
                    String flight = (String) scheduleMap.get("flight");

                    // Use getId(String.class)
                    LOGGER.debug("Checking schedule for route {}, day {}, target day {}", doc.getId(String.class), day, requestedDayOfWeek);
                    if (day == requestedDayOfWeek && utc != null && flight != null) {
                        // Construct flight data map matching original structure
                        Map<String, Object> flightPathData = new HashMap<>();
                        // flightPathData.put("airlineid", airlineId); // Remove airlineid
                        flightPathData.put("equipment", equipment);
                        flightPathData.put("flight", flight);
                        flightPathData.put("utc", utc);
                        flightPathData.put("sourceairport", sourceAirport);
                        flightPathData.put("destinationairport", destinationAirport);

                        // Fetch and add airline name using the key "name"
                        findAirlineName(airlineId).ifPresent(name -> flightPathData.put("name", name));

                        // Add date in MM/dd/yyyy format
                        DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT, Locale.US);
                        flightPathData.put("date", df.format(leave.getTime()));

                        // Simulate flighttime and price
                        int flightTime = random.nextInt(8000) + 1000; // Simulate time > 0
                        flightPathData.put("flighttime", flightTime);
                        flightPathData.put("price", Math.ceil((double) flightTime / 8.0 * 100.0) / 100.0);

                        finalFlightPaths.add(flightPathData);
                    }
                }
            });

        } catch (Exception e) {
            LOGGER.error("Failed during flight path processing for {} -> {}: {}", fromAirportFaa, toAirportFaa, e.getMessage(), e);
            // Return potentially partial results or empty list
        }

        LOGGER.info("Found {} valid flight paths for {} -> {} on day {}", finalFlightPaths.size(), from, to, requestedDayOfWeek);
        // Sort by airline name as in original query
        finalFlightPaths.sort(Comparator.comparing(m -> (String) m.getOrDefault("name", "")));
        return finalFlightPaths;
    }

    // Helper to find FAA code for an airport name
    private String findAirportFaa(String airportName) {
        if (airportCollection == null) return null;
        try {
            Optional<Document> airportDoc = airportCollection.findOne(
                    Filters.eq("airportname", airportName)
            );
            return airportDoc.map(doc -> doc.getString("faa")).orElse(null);
        } catch (Exception e) {
            LOGGER.error("Failed to find FAA for airport '{}': {}", airportName, e.getMessage(), e);
            return null;
        }
    }

    // Helper to find airline name by ID
    private Optional<String> findAirlineName(String airlineId) {
        if (airlineCollection == null) {
            LOGGER.error("Airline collection not available");
            return Optional.empty();
        }
        // Assuming airlineId from route corresponds to _id in airline collection
        Optional<Document> airlineDoc = airlineCollection.findOne(
                Filters.eq("_id", airlineId)
                // FindOneOptions options = FindOneOptions.builder().projection(Projection.include("name")).build();
        );
        // Map the Optional<Document> to Optional<String>
        return airlineDoc.map(doc -> doc.getString("name"));
    }

    // Removed static logQuery helper
}
