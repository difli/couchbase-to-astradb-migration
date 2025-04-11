package trycb.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;

import trycb.model.Error;
import trycb.service.Hotel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hotels")
@CrossOrigin(origins = "*")
public class HotelController {

    private static final Logger LOGGER = LoggerFactory.getLogger(HotelController.class);

    @Autowired
    private Hotel hotelService;

    @GetMapping(value = "/{description}/{location}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findHotelsByDescriptionAndLocation(@PathVariable String description, @PathVariable String location) {
        LOGGER.info("Received hotel search request: desc='{}', loc='{}'", description, location);
        return findHotels(location, description);
    }

    @GetMapping(value = "/{description}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findHotelsByDescription(@PathVariable String description) {
        LOGGER.info("Received hotel search request: desc='{}'", description);
        return findHotels("*", description);
    }

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> findHotelsByParams(@RequestParam(required = false) String location, @RequestParam(required = false) String description) {
        location = StringUtils.hasText(location) ? location : "*";
        description = StringUtils.hasText(description) ? description : "*";
        LOGGER.info("Received hotel search request (params): loc='{}', desc='{}'", location, description);
        return findHotels(location, description);
    }

    private ResponseEntity<?> findHotels(String location, String description) {
        try {
            List<Map<String, Object>> resultData = hotelService.findHotels(location, description);
            Map<String, Object> response = Map.of(
                "data", resultData,
                "context", Collections.emptyList()
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOGGER.error("Hotel search failed for loc='{}', desc='{}'", location, description, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Error("Search failed: " + e.getMessage()));
        }
    }
}
