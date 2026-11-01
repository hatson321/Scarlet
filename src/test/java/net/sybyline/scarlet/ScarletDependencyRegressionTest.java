package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;

class ScarletDependencyRegressionTest {
    @Test void synchronousAndAsyncJacksonEnforceNumberLength() throws Exception {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) value.append('1');
        byte[] input = value.append(']').toString().getBytes(StandardCharsets.UTF_8);
        JsonFactory factory = new JsonFactory();
        assertThrows(StreamConstraintsException.class, () -> {
            try (JsonParser parser = factory.createParser(input)) {
                while (parser.nextToken() != null) parser.getText();
            }
        });
        assertThrows(StreamConstraintsException.class, () -> {
            try (JsonParser parser = factory.createNonBlockingByteArrayParser()) {
                ByteArrayFeeder feeder = (ByteArrayFeeder)parser.getNonBlockingInputFeeder();
                feeder.feedInput(input, 0, input.length); feeder.endOfInput();
                while (parser.nextToken() != null) parser.getText();
            }
        });
    }
    @Test void oneHttpClientDefinitionAndNoLegacyLog4j() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals(1, Collections.list(loader.getResources("okhttp3/OkHttpClient.class")).size());
        assertFalse(loader.getResources("org/apache/log4j/Logger.class").hasMoreElements());
        assertFalse(loader.getResources("com/google/common/io/Files.class").hasMoreElements());
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .addInterceptor(new okhttp3.logging.HttpLoggingInterceptor()).build();
        assertNotNull(new io.github.vrchatapi.ApiClient(client));
        assertEquals("synthetic", net.dv8tion.jda.api.utils.data.DataObject
            .fromJson("{\"value\":\"synthetic\"}").getString("value"));
    }
}
