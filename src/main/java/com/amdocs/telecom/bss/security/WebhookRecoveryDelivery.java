package com.amdocs.telecom.bss.security;

import com.amdocs.telecom.bss.service.AuthenticationService.RecoveryDelivery;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Adapter for an operator-owned email/SMS delivery endpoint. Never logs codes or tokens. */
public final class WebhookRecoveryDelivery implements RecoveryDelivery {
    private final URI endpoint;
    private final String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WebhookRecoveryDelivery(String endpoint,String token) {
        this.endpoint=endpoint==null || endpoint.isBlank()?null:URI.create(endpoint); this.token=token;
        if(this.endpoint!=null && (!"https".equals(this.endpoint.getScheme()) || this.endpoint.getUserInfo()!=null))
            throw new IllegalArgumentException("Recovery delivery requires an HTTPS endpoint without embedded credentials");
    }
    private static String json(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
    @Override public void checkAvailability() {
        if(endpoint==null || token==null || token.isBlank()) throw new IllegalStateException("Password recovery delivery is not configured; contact the operator");
    }
    @Override public void send(String email,String code) {
        checkAvailability();
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"recipient\":\""+json(email)+"\",\"code\":\""+code+"\",\"expiresInSeconds\":300}")).build();
        try {
            int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            if(status<200 || status>=300) throw new IllegalStateException("Recovery delivery failed; contact the operator");
        } catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new IllegalStateException("Recovery delivery interrupted"); }
        catch(java.io.IOException ex) { throw new IllegalStateException("Recovery delivery unavailable; contact the operator"); }
    }
}
