package trycb.service;

// Removed Couchbase imports
// import static com.couchbase.client.java.kv.InsertOptions.insertOptions;
// import com.couchbase.client.core.error.DocumentNotFoundException;
// import com.couchbase.client.core.msg.kv.DurabilityLevel;
// import com.couchbase.client.java.Bucket;
// import com.couchbase.client.java.Collection;
// import com.couchbase.client.java.Scope;
// import com.couchbase.client.java.json.JsonArray;
// import com.couchbase.client.java.json.JsonObject;
// import com.couchbase.client.java.kv.GetResult;
// import com.couchbase.client.java.kv.InsertOptions;

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
// import com.datastax.astra.client.model.InsertOneResult;
// Corrected path for Update/Updates
import com.datastax.astra.client.collections.commands.Update;
import com.datastax.astra.client.collections.commands.Updates;
// import com.datastax.astra.client.model.UpdateResult;

// Spring and Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException; // For insert conflict
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

// Standard Java Imports
import java.util.*;
import java.util.stream.Collectors;

// Spring Util import needed for CollectionUtils
import org.springframework.util.CollectionUtils;

// Removed Result import
// import trycb.model.Result;

@Service
public class TenantUser {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantUser.class);
    static final String USERS_COLLECTION_NAME = "user"; // Assuming singular based on convention
    static final String BOOKINGS_COLLECTION_NAME = "booking";

    private final TokenService jwtService;
    private final Database astraDatabase;
    private final String keyspace;
    private Collection<Document> usersCollection;
    private Collection<Document> bookingsCollection;

    @Autowired
    public TenantUser(Database astraDatabase, @Value("${astra.api.keyspace}") String keyspace, TokenService jwtService) {
        this.astraDatabase = astraDatabase;
        this.keyspace = keyspace;
        this.jwtService = jwtService;
        try {
            this.usersCollection = astraDatabase.getCollection(USERS_COLLECTION_NAME, Document.class);
            this.bookingsCollection = astraDatabase.getCollection(BOOKINGS_COLLECTION_NAME, Document.class);
            LOGGER.info("TenantUser Service connected to Astra collections ('{}', '{}') in keyspace '{}'",
                    USERS_COLLECTION_NAME, BOOKINGS_COLLECTION_NAME, keyspace);
        } catch (Exception e) {
            LOGGER.error("Failed to get Astra collections ('{}', '{}') in keyspace '{}'",
                    USERS_COLLECTION_NAME, BOOKINGS_COLLECTION_NAME, keyspace, e);
            this.usersCollection = null; // Mark as unusable
            this.bookingsCollection = null;
        }
    }

    /**
     * Try to log the given tenant user in using Astra DB.
     */
    public Map<String, Object> login(final String username, final String password) {
        if (usersCollection == null) {
             throw new AuthenticationServiceException("User collection not available");
        }

        // Fetch user document by username (_id)
        Optional<Document> userDocOpt = usersCollection.findOne(Filters.eq("_id", username));

        if (userDocOpt.isEmpty()) {
             LOGGER.warn("Login attempt failed: User '{}' not found.", username);
            throw new AuthenticationCredentialsNotFoundException("Bad Username or Password");
        }

        Document userDoc = userDocOpt.get();
        String storedHash = userDoc.getString("password");

        if (storedHash != null && BCrypt.checkpw(password, storedHash)) {
            LOGGER.info("User '{}' logged in successfully.", username);
            String token = jwtService.buildToken(username);
            // Match original response structure: { data: { token: ... }, context: [...] }
            return Map.of(
                "data", Map.of("token", token),
                "context", Collections.emptyList() // Empty context list
            );
        } else {
             LOGGER.warn("Login attempt failed: Incorrect password for user '{}'.", username);
            throw new AuthenticationCredentialsNotFoundException("Bad Username or Password");
        }
    }

    /**
     * Create a tenant user in Astra DB.
     */
    public Map<String, Object> createLogin(final String username, final String password) {
        if (usersCollection == null) {
            throw new AuthenticationServiceException("User collection not available");
        }

        String passHash = BCrypt.hashpw(password, BCrypt.gensalt());

        Document newUserDoc = new Document()
                .id(username) // Use username as the document _id
                .append("type", "user")
                .append("name", username)
                .append("password", passHash);
        // Initialize flights array
        newUserDoc.append("flights", new ArrayList<String>());

        try {
            // insertOne likely returns void or throws exception in this SDK version
            usersCollection.insertOne(newUserDoc);
            // Removed check based on InsertOneResult
            // if (!result.getInsertedId().equals(username)) {
            //     LOGGER.error("User creation inserted ID mismatch for '{}'! Expected: {}, Got: {}", username, username, result.getInsertedId());
            //     throw new AuthenticationServiceException("Account creation failed unexpectedly.");
            // }
            LOGGER.info("User '{}' created successfully.", username);
            return Map.of("token", jwtService.buildToken(username));
        } catch (DuplicateKeyException e) { // Assuming Astra client might throw something like this on _id conflict
             LOGGER.warn("User creation failed: Username '{}' already exists.", username);
            throw new AuthenticationServiceException("Username already exists", e);
        } catch (Exception e) {
             LOGGER.error("User creation failed for '{}': {}", username, e.getMessage(), e);
            throw new AuthenticationServiceException("Error creating account: " + e.getMessage(), e);
        }
    }

    /*
     * Register a flight (or flights) for the given tenant user using Astra DB.
     */
    public Map<String, Object> registerFlightForUser(final String username, final List<Map<String, Object>> newFlights) {
        // Log the list received by the service
        LOGGER.debug("Service received newFlights list: {}", newFlights);

        if (usersCollection == null || bookingsCollection == null) {
            throw new IllegalStateException("User or Booking collection not available");
        }

        // 1. Fetch the user document first to get current bookings
        Optional<Document> userDocOpt = usersCollection.findOne(Filters.eq("_id", username));
        if (userDocOpt.isEmpty()) {
            LOGGER.error("Cannot register flight: User '{}' not found.", username);
            throw new IllegalStateException("User not found");
        }
        Document userDoc = userDocOpt.get();
        List<String> currentFlightIds = userDoc.get("flights", List.class);
        if (currentFlightIds == null) {
            currentFlightIds = new ArrayList<>();
        }

        if (CollectionUtils.isEmpty(newFlights)) {
            throw new IllegalArgumentException("No flights provided in payload");
        }

        List<String> addedFlightIds = new ArrayList<>();
        List<Map<String, Object>> addedFlightData = new ArrayList<>();

        // 2. Insert new bookings
        for (Map<String, Object> newFlight : newFlights) {
            // Log the object being passed to checkFlight and its class
            LOGGER.debug("Looping - Object to be checked: class={}, value={}",
                (newFlight == null ? "null" : newFlight.getClass().getName()), newFlight);

            checkFlight(newFlight); // Validate flight data
            String flightId = UUID.randomUUID().toString();

            Document bookingDoc = new Document().id(flightId);
            // Copy data from the input map
            bookingDoc.putAll(newFlight);
            bookingDoc.put("bookedon", "try-cb-java"); // Add booking source

            try {
                bookingsCollection.insertOne(bookingDoc);
                addedFlightIds.add(flightId);
                addedFlightData.add(newFlight); // Keep original data for response
                LOGGER.debug("Inserted booking {} for user {}", flightId, username);
            } catch (Exception e) {
                 LOGGER.error("Failed to insert booking for user {}: {}", username, e.getMessage(), e);
                 // Decide on behavior: continue, stop, collect errors?
                 // For now, log and continue, but don't add to user's list
            }
        }

        // 3. Update user document if any bookings were successfully added
        if (!addedFlightIds.isEmpty()) {
            List<String> allBookingIds = new ArrayList<>(currentFlightIds);
            allBookingIds.addAll(addedFlightIds);

            try {
                // Update the user document with the new list of booking IDs
                Update update = Updates.set("flights", allBookingIds);
                // updateOne likely returns void or throws exception in this SDK version
                usersCollection.updateOne(Filters.eq("_id", username), update);
                // Removed check based on UpdateResult
                // if (updateResult.getModifiedCount() != 1) {
                //    LOGGER.warn("User '{}' update might have failed, modified count: {}", username, updateResult.getModifiedCount());
                //    // Consider potential inconsistency
                // }
                LOGGER.info("Updated user '{}' with {} new booking IDs.", username, addedFlightIds.size());
            } catch (Exception e) {
                 LOGGER.error("Failed to update user '{}' with new booking IDs: {}", username, e.getMessage(), e);
                 // State might be inconsistent: bookings inserted but user not updated
                 throw new RuntimeException("Failed to finalize flight registration for user " + username, e);
            }
        }

        return Map.of("added", addedFlightData);
    }

    // Adapted checkFlight to work with Map
    private static void checkFlight(Map<String, Object> flight) {
        // Log the map received IMMEDIATELY upon entry
        LOGGER.info("Entering checkFlight with map: {}", flight);

        if (flight == null) {
            LOGGER.error("checkFlight received null map");
            throw new IllegalArgumentException("Each flight must be a non-null object");
        }

        // Basic containsKey checks
        if (!flight.containsKey("name") ||
            !flight.containsKey("date") ||
            !flight.containsKey("sourceairport") ||
            !flight.containsKey("destinationairport")) {
            // Log keyset if check fails
            LOGGER.warn("checkFlight validation failed for keys: {}, map: {}", flight.keySet(), flight);
            throw new IllegalArgumentException("Malformed flight inside flights payload: " + flight.toString());
        }
        LOGGER.info("checkFlight passed for map: {}", flight);
    }

    /**
     * Get flights booked by a user using Astra DB.
     */
    public List<Map<String, Object>> getFlightsForUser(final String username) {
        if (usersCollection == null || bookingsCollection == null) {
            LOGGER.error("User or Booking collection not available for getFlightsForUser");
            return Collections.emptyList();
        }

        // 1. Get user document
        Optional<Document> userDocOpt = usersCollection.findOne(Filters.eq("_id", username));
        if (userDocOpt.isEmpty()) {
            LOGGER.warn("User '{}' not found when retrieving flights.", username);
            return Collections.emptyList();
        }

        // 2. Extract booking IDs
        Document userDoc = userDocOpt.get();
        List<String> flightIdList = userDoc.getList("flights", String.class);
        if (CollectionUtils.isEmpty(flightIdList)) {
            LOGGER.info("User '{}' has no booked flights.", username);
            return Collections.emptyList();
        }

        // Workaround for apparent $in operator bug in SDK/API preview
        // Fetch bookings one by one instead of using Filters.in()
        List<Map<String, Object>> results = new ArrayList<>();
        for (String flightId : flightIdList) {
            if (flightId == null || flightId.trim().isEmpty()) continue; // Skip invalid IDs
            try {
                Optional<Document> bookingDocOpt = bookingsCollection.findOne(Filters.eq("_id", flightId));
                if (bookingDocOpt.isPresent()) {
                    results.add(bookingDocOpt.get().getDocumentMap());
                } else {
                    LOGGER.warn("Booking document not found for ID: {} listed in user '{}' flights", flightId, username);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to retrieve individual booking for ID {} for user '{}': {}", flightId, username, e.getMessage(), e);
                // Decide whether to continue or fail fast - continuing for now
            }
        }
        LOGGER.info("Retrieved {} booking documents individually for user '{}'", results.size(), username);

        return results;
    }

}
