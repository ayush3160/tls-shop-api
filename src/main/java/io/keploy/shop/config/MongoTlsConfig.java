package io.keploy.shop.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * Configures TLS for the MongoDB driver connection.
 *
 * <p>Design note: this is standard <b>CA-based TLS</b>, deliberately <b>not certificate
 * pinning</b>. We never hardcode a specific server certificate or public-key hash; instead we
 * trust any certificate that chains up to a trusted CA (the JVM default truststore, or an
 * operator-supplied truststore). That means the MongoDB server can rotate its leaf certificate
 * without any client change — the property a pinned client would break on.
 *
 * <p>The {@code allow-invalid-certs} switch is a dev-only escape hatch for self-signed servers;
 * it disables validation entirely and must never be enabled in production.
 */
@Configuration
public class MongoTlsConfig {

    @Value("${app.mongo.tls.enabled:true}")
    private boolean tlsEnabled;

    @Value("${app.mongo.tls.allow-invalid-certs:false}")
    private boolean allowInvalidCerts;

    @Value("${app.mongo.tls.trust-store:}")
    private String trustStorePath;

    @Value("${app.mongo.tls.trust-store-password:}")
    private String trustStorePassword;

    @Value("${app.mongo.tls.trust-store-type:PKCS12}")
    private String trustStoreType;

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTlsCustomizer() {
        return (MongoClientSettings.Builder builder) -> {
            if (!tlsEnabled) {
                return;
            }
            builder.applyToSslSettings(ssl -> {
                ssl.enabled(true);
                // Never invalid-hostname unless we're also allowing invalid certs (dev only).
                ssl.invalidHostNameAllowed(allowInvalidCerts);
                try {
                    ssl.context(buildSslContext());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to build Mongo TLS SSLContext", e);
                }
            });
        };
    }

    private SSLContext buildSslContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");

        if (allowInvalidCerts) {
            // DEV ONLY: trust everything. Still encrypted, but no authentication.
            ctx.init(null, new TrustManager[]{trustAllManager()}, null);
            return ctx;
        }

        TrustManager[] trustManagers;
        if (StringUtils.hasText(trustStorePath)) {
            // Trust the CAs in the operator-supplied truststore (still not pinning a leaf cert).
            KeyStore ks = KeyStore.getInstance(trustStoreType);
            try (InputStream in = Files.newInputStream(Path.of(trustStorePath))) {
                ks.load(in, StringUtils.hasText(trustStorePassword)
                        ? trustStorePassword.toCharArray() : null);
            }
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            trustManagers = tmf.getTrustManagers();
        } else {
            // Default = JVM system CA truststore. Standard, non-pinned validation.
            trustManagers = null;
        }

        ctx.init(null, trustManagers, null);
        return ctx;
    }

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    /** Exposes a connection string bean so other components can read the configured DB name, etc. */
    @Bean
    public ConnectionString mongoConnectionString(
            @Value("${spring.data.mongodb.uri}") String uri) {
        return new ConnectionString(uri);
    }
}
