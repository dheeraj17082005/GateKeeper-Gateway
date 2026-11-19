package in.coderarmy.gatekeeper.gateway.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class UpstreamClient {
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    HttpResponse<byte[]> send(HttpRequest request, int timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        var response = client.sendAsync(request, ignored -> new LimitedBody());
        try {
            return response.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException exception) {
            response.cancel(true);
            throw exception;
        }
    }

    static URI target(String baseUrl, String targetPrefix, String remainingPath, String query) {
        URI base = URI.create(baseUrl);
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null
                || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))) {
            throw new IllegalArgumentException("Invalid upstream base URL");
        }

        String path = join(join(base.getRawPath(), targetPrefix), remainingPath);
        URI target = URI.create(base.getScheme() + "://" + base.getRawAuthority() + path
                + (query == null ? "" : "?" + query));
        if (!path.equals(target.getRawPath()) || target.getRawFragment() != null || containsDotSegment(path)) {
            throw new IllegalArgumentException("Invalid upstream path");
        }
        return target;
    }

    private static boolean containsDotSegment(String path) {
        for (String segment : path.split("/")) {
            String decodedDots = segment.split(";", 2)[0].replaceAll("(?i)%2e", ".");
            if (decodedDots.equals(".") || decodedDots.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String join(String prefix, String suffix) {
        if (suffix.isEmpty()) {
            return prefix;
        }
        return (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    static String origin(URI uri) {
        if (uri.getHost() == null || uri.getRawUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Expected an HTTP(S) origin");
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytesReceived;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                bytesReceived += buffer.remaining();
            }
            if (bytesReceived > MAX_BODY_BYTES) {
                subscription.cancel();
                delegate.onError(new IOException("Upstream response exceeds size limit"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
