package ru.smotrim.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.io.IOException;
import okhttp3.OkHttpClient;

/** The signature-protected provider shares a session-only loopback proxy, never subscription data. */
final class SearchVpn {
    private static final Uri CONTROL = Uri.parse("content://ru.smotrim.vpn.filter/state");
    private final Context context;
    SearchVpn(Context context) { this.context = context; }

    String get(String url) throws IOException {
        if (context.getPackageManager().resolveContentProvider(CONTROL.getAuthority(), 0) == null)
            return Sources.text(url);
        Bundle state;
        try { state = context.getContentResolver().call(CONTROL, "state", null, null); }
        catch (RuntimeException e) { throw new Sources.SearchUnavailable("Не удалось связаться со «Смотрим VPN»"); }
        if (state == null) throw new Sources.SearchUnavailable("Не удалось проверить состояние VPN");
        String phase = state.getString("phase", "");
        if ("OFF".equals(phase) || "ERROR".equals(phase)) return Sources.text(url);
        if (!"CONNECTED".equals(phase) && !"WAITING".equals(phase))
            throw new Sources.SearchUnavailable("VPN подключается. Повторите поиск через несколько секунд");
        Bundle route;
        try { route = context.getContentResolver().call(CONTROL, "search-route", null, null); }
        catch (IllegalArgumentException e) { throw new Sources.SearchUnavailable("Обновите «Смотрим VPN» для поиска через VPN"); }
        catch (RuntimeException e) { throw new Sources.SearchUnavailable("Не удалось получить подключение для поиска"); }
        if (route == null || route.getInt("version") != 1) throw new Sources.SearchUnavailable("Обновите «Смотрим VPN»");
        OkHttpClient client = SearchHttp.client(route.getInt("port"), route.getString("password"));
        try { return SearchHttp.get(url, client); }
        finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
