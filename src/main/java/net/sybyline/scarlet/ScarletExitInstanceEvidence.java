package net.sybyline.scarlet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.Response;

/** Numeric/cache diagnostics only: no response bodies, identities, URLs, cookies or arbitrary headers. */
final class ScarletExitInstanceEvidence
{
    private ScarletExitInstanceEvidence() {}
    static String describe(JsonElement raw, Response response)
    {
        JsonObject object = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
        JsonElement users = object.get("users");
        String age = response.header("Age"), cache = response.header("CF-Cache-Status");
        if (age == null || !age.matches("[0-9]{1,10}")) age = "not-supplied-or-invalid";
        if (cache == null || !cache.matches("HIT|MISS|DYNAMIC|BYPASS|EXPIRED|STALE|REVALIDATED|UPDATING")) cache = "not-supplied-or-other";
        return "endpoint=instances-primary; n_users=" + number(object.get("n_users"))
            + "; userCount=" + number(object.get("userCount"))
            + "; listedUsers=" + (users != null && users.isJsonArray() ? users.getAsJsonArray().size() : "not-supplied-or-invalid")
            + "; cacheAgeSeconds=" + age + "; edgeCache=" + cache
            + "; localCache=" + (response.cacheResponse() != null) + "; networkResponse=" + (response.networkResponse() != null);
    }
    private static String number(JsonElement value)
    {
        if (value == null || value.isJsonNull()) return "not-supplied";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return "invalid";
        String text = value.getAsString();
        return text.matches("-?[0-9]{1,10}") ? text : "invalid";
    }
}
