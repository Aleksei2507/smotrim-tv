package ru.smotrim.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Only indexer requests use this client. Media, peers and tracker traffic never do. */
final class SearchHttp {
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "jac.red", "jac-red.ru", "rutor.info", "www.rutor.info", "apibay.org"));
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private SearchHttp() {}

    static boolean allowed(HttpUrl url) {
        return url != null && url.isHttps() && url.port() == 443 && HOSTS.contains(url.host())
                && url.username().isEmpty() && url.password().isEmpty();
    }

    static OkHttpClient client(int port, String password) throws IOException {
        if (port < 1 || port > 65535 || password == null || password.isEmpty())
            throw new IOException("VPN ещё не готов к поиску");
        String credential = Credentials.basic("search", password);
        return new OkHttpClient.Builder()
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)))
                .proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder().header("Proxy-Authorization", credential).build();
                })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS).build();
    }

    static String get(String address, OkHttpClient client) throws IOException {
        HttpUrl url = HttpUrl.parse(address);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        for (int redirect = 0; redirect <= 4; redirect++) {
            if (!allowed(url)) throw new IOException("Недопустимый адрес источника поиска");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Время поиска истекло");
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "SmotrimTV/" + BuildConfig.VERSION_NAME + " (Android TV)")
                    .header("Accept", "application/json,text/html").build();
            okhttp3.Call call = client.newCall(request);
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            try (Response response = call.execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    url = location == null ? null : url.resolve(location);
                    continue;
                }
                if (!response.isSuccessful()) throw new IOException("Источник ответил HTTP " + response.code());
                if (response.body() == null) throw new IOException("Пустой ответ источника");
                try (InputStream in = response.body().byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        if (out.size() + count > MAX_BYTES) throw new IOException("Ответ источника слишком большой");
                        out.write(buffer, 0, count);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("Слишком много перенаправлений источника");
    }
}
