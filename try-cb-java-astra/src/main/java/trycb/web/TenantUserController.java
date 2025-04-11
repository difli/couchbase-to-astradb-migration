package trycb.web;

import java.util.List;
import java.util.Map;
import java.util.Collections;

// Removed Couchbase imports
// import com.couchbase.client.core.msg.kv.DurabilityLevel;
// import com.couchbase.client.java.Bucket;
// import com.couchbase.client.java.json.JsonObject;
// import com.couchbase.client.java.json.JsonArray; // Needed for parsing later?

// Jackson Import for JSON parsing
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

// Spring and Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

// Model Imports
import trycb.model.Error; // Keep for error response
// import trycb.model.IValue; // Removed
// import trycb.model.Result; // Removed

// Service Imports
import trycb.service.TenantUser;
import trycb.service.TokenService;

@RestController
@RequestMapping("/api/tenants/{tenant}/user")
@CrossOrigin(origins = "*") // Allowing requests from any origin
public class TenantUserController {

    // Removed Bucket field
    // private final Bucket bucket;
    private final TenantUser tenantUserService;
    private final TokenService jwtService;
    private final ObjectMapper objectMapper; // For parsing JSON request body

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantUserController.class);

    // Removed expiry value as DurabilityLevel is not used
    // @Value("${storage.expiry:0}")
    // private int expiry;

    @Autowired
    public TenantUserController(TenantUser tenantUserService, TokenService jwtService, ObjectMapper objectMapper) {
        // Removed Bucket injection
        this.tenantUserService = tenantUserService;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/login")
    // Add tenant path variable (unused)
    public ResponseEntity<?> login(@PathVariable String tenant, @RequestBody Map<String, String> loginInfo) {
        LOGGER.info("Received login request for tenant: {}", tenant); // Log tenant for info
        String user = loginInfo.get("user");
        String password = loginInfo.get("password");
        if (user == null || password == null) {
            return ResponseEntity.badRequest().body(new Error("User or password missing, or malformed request"));
        }

        try {
            // Call refactored service method (no bucket/tenant)
            Map<String, Object> result = tenantUserService.login(user, password);
            return ResponseEntity.ok(result);
        } catch (AuthenticationException e) {
            LOGGER.error("Login failed for user '{}': {}", user, e.getMessage()); // Log username
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Error(e.getMessage()));
        } catch (Exception e) {
            LOGGER.error("Login internal error for user '{}'", user, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Login failed: " + e.getMessage()));
        }
    }

    @PostMapping("/signup")
    // Add tenant path variable (unused)
    public ResponseEntity<?> createLogin(@PathVariable String tenant, @RequestBody Map<String, String> signupInfo) {
        LOGGER.info("Received signup request for tenant: {}", tenant); // Log tenant for info
        String user = signupInfo.get("user");
        String password = signupInfo.get("password");
        if (user == null || password == null) {
            return ResponseEntity.badRequest().body(new Error("User or password missing, or malformed request"));
        }

        try {
            // Call refactored service method (no bucket/tenant/durability)
            Map<String, Object> resultData = tenantUserService.createLogin(user, password);
            // Wrap response in { data: ..., context: [] }
            Map<String, Object> response = Map.of(
                "data", resultData,
                "context", Collections.emptyList()
            );
            // Return wrapped result directly with CREATED status
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AuthenticationServiceException e) { // Assuming service throws this for duplicates
            LOGGER.warn("Signup failed for user '{}': {}", user, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(e.getMessage()));
        } catch (Exception e) {
            LOGGER.error("Signup internal error for user '{}'", user, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Signup failed: " + e.getMessage()));
        }
    }

    @PutMapping("/{username}/flights")
    // Add username path variable, keep tenant (unused)
    // Expect the wrapper object { "flights": [...] }
    public ResponseEntity<?> book(@PathVariable String tenant, // Keep for path structure
                                  @PathVariable String username, // Get username from path
                                  @RequestBody Map<String, Object> payload, // Correct parameter name
                                  @RequestHeader("Authorization") String authentication) {
        LOGGER.info("Received booking request payload for tenant: {}, user: {}: {}", tenant, username, payload);
        if (authentication == null || !authentication.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Error("Bearer Authentication must be used"));
        }

        try {
            // Verify against username from path
            jwtService.verifyAuthenticationHeader(authentication, username);

            // Extract the list of flights from the payload map
            Object flightsObject = payload.get("flights"); // Use correct variable name 'payload'
            if (!(flightsObject instanceof List)) {
                LOGGER.error("Invalid booking payload structure for user '{}': 'flights' key is missing or not a list. Payload: {}", username, payload);
                return ResponseEntity.badRequest().body(new Error("Invalid payload structure: 'flights' array missing."));
            }
            // This cast is potentially unsafe if list contains non-Map items, but matches expected structure
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> flightsList = (List<Map<String, Object>>) flightsObject;

            // Ensure the incorrect singletonList line is removed
            // List<Map<String, Object>> flightsList = Collections.singletonList(payload); // Incorrect

            // Log the list being passed to the service
            LOGGER.debug("Controller passing flightsList to service: {}", flightsList);

            // Call refactored service method with the extracted list
            Map<String, Object> result = tenantUserService.registerFlightForUser(username, flightsList);
            // Wrap response in { data: ..., context: [] }
            Map<String, Object> response = Map.of(
                "data", result,
                "context", Collections.emptyList()
            );
            return ResponseEntity.ok().body(response);
        } catch (IllegalStateException e) { // Assuming service throws this for user not found or auth mismatch
            LOGGER.warn("Booking forbidden for user '{}': {}", username, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Error(e.getMessage())); // Use service message
        } catch (IllegalArgumentException e) { // Assuming service throws this for bad flight data
            LOGGER.warn("Invalid booking data for user '{}': {}", username, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Error(e.getMessage()));
        } catch (Exception e) { // Catch-all for other errors
             LOGGER.error("Booking internal error for user '{}'", username, e);
             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Booking failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{username}/flights")
    // Add username path variable, keep tenant (unused)
    public ResponseEntity<?> booked(@PathVariable String tenant, // Keep for path structure
                                    @PathVariable String username, // Get username from path
                                    @RequestHeader("Authorization") String authentication) {
        LOGGER.info("Received get booked flights request for tenant: {}, user: {}", tenant, username);
        if (authentication == null || !authentication.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Error("Bearer Authentication must be used"));
        }

        try {
            // Verify against username from path
            jwtService.verifyAuthenticationHeader(authentication, username);
            // Call refactored service method with username from path
            List<Map<String, Object>> resultData = tenantUserService.getFlightsForUser(username);
            // Wrap response in { data: ..., context: [] }
            Map<String, Object> response = Map.of(
                "data", resultData,
                "context", Collections.emptyList()
            );
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) { // Assuming service throws this for user not found or auth mismatch
              LOGGER.warn("Access forbidden to flights for user '{}': {}", username, e.getMessage());
            // Reuse service message or provide generic forbidden
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Error(e.getMessage()));
        } catch (Exception e) {
              LOGGER.error("Get flights internal error for user '{}'", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new Error("Failed to retrieve booked flights: " + e.getMessage()));
        }
    }

}
