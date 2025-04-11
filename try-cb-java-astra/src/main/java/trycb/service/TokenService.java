package trycb.service;

// Removed Couchbase imports
// import com.couchbase.client.core.deps.io.netty.util.CharsetUtil;
// import com.couchbase.client.java.json.JsonObject;

// Standard Java Imports
import java.nio.charset.StandardCharsets;
import java.util.Map;

// JWT Imports
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Claims;

// Spring Imports
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Base64Utils;

@Service
public class TokenService {


    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.enabled}")
    private boolean useJwt;

    /**
     * @throws IllegalStateException when the Authorization header couldn't be verified or didn't match the expected
     * username.
     */
    public void verifyAuthenticationHeader(String authorization, String expectedUsername) {
        String token = authorization.replaceFirst("Bearer ", "");
        String tokenName;
        if (useJwt) {
            tokenName = verifyJwt(token);
        } else {
            tokenName = verifySimple(token);
        }
        if (!expectedUsername.equals(tokenName)) {
            throw new IllegalStateException("Token and username don't match");
        }
    }

    private String verifyJwt(String token) {
        try {
            String username = Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token)
                    .getBody()
                    .get("user", String.class);
            return username;
        } catch (JwtException e) {
            throw new IllegalStateException("Could not verify JWT token", e);
        }
    }

    private String verifySimple(String token) {
        try {
            return new String(Base64Utils.decodeFromString(token));
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify simple token", e);
        }
    }

    public String buildToken(String username) {
        if (useJwt) {
            return buildJwtToken(username);
        } else {
            return buildSimpleToken(username);
        }
    }

    private String buildJwtToken(String username) {
        // Use a Map for claims instead of JsonObject
        Claims claims = Jwts.claims();
        claims.put("user", username);

        String token = Jwts.builder()
                .setClaims(claims)
                .signWith(SignatureAlgorithm.HS512, secret)
                .compact();
        return token;
    }

    private String buildSimpleToken(String username) {
        // Use StandardCharsets.UTF_8
        return Base64Utils.encodeToString(username.getBytes(StandardCharsets.UTF_8));
    }
}
