package net.sybyline.scarlet;

import static org.junit.jupiter.api.Assertions.*;
import static net.sybyline.scarlet.ScarletDiscordSafetyTest.inert;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import gnu.trove.map.hash.TLongObjectHashMap;
import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.api.InstancesApi;
import net.dv8tion.jda.api.interactions.commands.CommandInteractionPayload;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.sybyline.scarlet.util.Location;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Actual slash-option and SDK request construction, with synthetic IDs and no network. */
class ScarletCloseInstanceCommandInputTest
{
    private static String optionValue(String input) throws Exception
    {
        ScarletDiscordCommands commands = inert(ScarletDiscordCommands.class);
        ScarletDiscordCommands.VrchatGroups groupCommands = commands.new VrchatGroups();
        OptionMapping mapping = new OptionMapping(DataObject.empty().put("name", "vrchat-location")
            .put("type", 3).put("value", input), new TLongObjectHashMap<>(), null, null);
        CommandInteractionPayload interaction = (CommandInteractionPayload)Proxy.newProxyInstance(
            ScarletCloseInstanceCommandInputTest.class.getClassLoader(), new Class<?>[]{CommandInteractionPayload.class},
            (proxy, method, arguments) -> method.getName().equals("getOption") ? mapping : null);
        return groupCommands._vrchatLocation.get(interaction);
    }

    @Test void registeredOptionDoesNotRunTheLegacyResolverOnAgeGatedFullLinks() throws Exception
    {
        String parsed = optionValue(ScarletCloseInstanceInputTest.URL);
        assertEquals(ScarletCloseInstanceInputTest.URL, parsed);
        assertEquals(ScarletCloseInstanceInputTest.INSTANCE,
            ScarletCloseInstanceInput.parse(parsed, ScarletCloseInstanceInputTest.GROUP).instance);
    }

    @Test void registeredOptionLeavesInvalidInputForTheHandlersHelpfulValidation() throws Exception
    {
        String parsed = optionValue(ScarletCloseInstanceInputTest.WORLD);
        assertEquals(ScarletCloseInstanceInputTest.WORLD, parsed);
        assertEquals(ScarletCloseInstanceInput.INVALID,
            assertThrows(IllegalArgumentException.class,
                () -> ScarletCloseInstanceInput.parse(parsed, ScarletCloseInstanceInputTest.GROUP)).getMessage());
    }

    @Test void validatedSlashInputBuildsTheCompleteSdkCloseRequestWithoutMissingParameters() throws Exception
    {
        Location parsed = ScarletCloseInstanceInput.parse(optionValue(ScarletCloseInstanceInputTest.URL),
            ScarletCloseInstanceInputTest.GROUP);
        List<Request> requests = new ArrayList<>();
        OkHttpClient http = new OkHttpClient.Builder()
            .dns(host -> { throw new AssertionError("Network forbidden"); })
            .addInterceptor(chain -> {
                requests.add(chain.request());
                assertEquals("close.invalid", chain.request().url().host());
                assertNull(chain.request().header("Authorization"));
                assertNull(chain.request().header("Cookie"));
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(404).message("Synthetic response")
                    .body(ResponseBody.create(MediaType.parse("application/json"), "{}"))
                    .build();
            }).build();
        try
        {
            InstancesApi api = new InstancesApi(new ApiClient(http).setBasePath("https://close.invalid/api/1"));
            ApiException reply = assertThrows(ApiException.class,
                () -> api.closeInstance(parsed.world, parsed.instance, false, null));
            assertEquals(404, reply.getCode(), "The synthetic transport must receive the request, not SDK parameter failure code 0");
            assertEquals(1, requests.size());
            assertTrue(requests.get(0).url().pathSegments().contains(ScarletCloseInstanceInputTest.RAW),
                requests.get(0).url().toString());
        }
        finally
        {
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
        }
    }
}
