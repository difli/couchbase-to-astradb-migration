package trycb.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import trycb.model.Error;
import trycb.service.Airport;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/airports")
@CrossOrigin(origins = "*")
public class AirportController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AirportController.class);

    @Autowired
    private Airport airportService;

    @GetMapping()
    public ResponseEntity<?> getAirport(@RequestParam String search) {
        if (!StringUtils.hasText(search) || search.length() < 2) {
            return ResponseEntity.badRequest()
                    .body(new Error("Parameter \"search\" must be provided and be at least 2 characters long."));
        }
        try {
            List<Map<String, Object>> resultData = airportService.findAll(search);
            Map<String, Object> response = Map.of(
                "data", resultData,
                "context", Collections.emptyList()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOGGER.error("Airport search failed for search '{}'", search, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Search failed: " + e.getMessage()));
        }
    }

}
