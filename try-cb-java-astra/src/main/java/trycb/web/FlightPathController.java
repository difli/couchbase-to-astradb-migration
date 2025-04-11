package trycb.web;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import trycb.model.Error;
import trycb.service.FlightPath;

@RestController
@RequestMapping("/api/flightPaths")
@CrossOrigin(origins = "*")
public class FlightPathController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlightPathController.class);

    @Autowired
    private FlightPath flightPathService;

    @GetMapping("/{from}/{to}")
    public ResponseEntity<?> getFlightPaths(@PathVariable String from,
                                           @PathVariable String to,
                                           @RequestParam String leave) {
        LOGGER.info("Received flight path request: date: {}, from: {}, to: {}", leave, from, to);
        try {
            // Parse the leaveDate string (now from 'leave' param) into a Calendar object
            Calendar calendar = Calendar.getInstance(Locale.US);
            try {
                // Assuming SHORT date format like MM/dd/yy used in original code
                calendar.setTime(DateFormat.getDateInstance(DateFormat.SHORT, Locale.US).parse(leave));
            } catch (java.text.ParseException pe) {
                LOGGER.warn("Failed to parse leave date '{}'", leave, pe);
                return ResponseEntity.badRequest().body(new Error("Invalid date format. Use format like MM/dd/yy."));
            }

            // Call the correct service method with Calendar object
            List<Map<String, Object>> resultData = flightPathService.findAll(from, to, calendar);
            // Wrap response in { data: ..., context: [] }
            Map<String, Object> response = Map.of(
                "data", resultData,
                "context", Collections.emptyList()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOGGER.error("Flight path search failed for date '{}', from '{}', to '{}'", leave, from, to, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Search failed: " + e.getMessage()));
        }
    }

}
