package trycb.model;

/**
 * A standardized error format for failing responses, that the frontend
 * application can interpret for all endpoints.
 */
public class Error {

    private final String message;
    private final int code = -1;

    public Error(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
