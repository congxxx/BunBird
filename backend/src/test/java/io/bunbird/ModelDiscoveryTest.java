package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class ModelDiscoveryTest {
    HttpServer server;
    String base;
    ModelDiscovery discovery=new ModelDiscovery(new ObjectMapper());
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void findsRootEndpointForAnthropicPrefixAndSanitizesModelList() {
        var paths=new ArrayList<String>();
        server.createContext("/",exchange->{
            paths.add(exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");
            assertThat(exchange.getRequestHeaders().getFirst("x-api-key")).isEqualTo("test-secret");
            int status=exchange.getRequestURI().getPath().equals("/v1/models")?200:404;
            byte[] body="{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"},{\"id\":\"test-secret\"},{\"id\":\"bad model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        assertThat(discovery.fetch(base+"/anthropic/","test-secret","anthropic")).containsExactly("a-model","z-model");
        assertThat(paths).containsExactly("/anthropic/v1/models","/anthropic/models","/v1/models");
        assertThat(ModelDiscovery.endpoints("https://api.deepseek.com/anthropic")).containsExactly("https://api.deepseek.com/models");
    }
    @Test void authErrorsAreRedactedAndNotRetried() {
        var count=new AtomicInteger();
        server.createContext("/",exchange->{
            count.incrementAndGet();byte[] body="test-secret echoed by provider".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        assertThatThrownBy(()->discovery.fetch(base,"test-secret","compatible")).hasMessageContaining("认证失败").hasMessageNotContaining("test-secret");
        assertThat(count.get()).isEqualTo(1);
    }
    @Test void redirectsNeverReceiveCredentials() {
        var forwarded=new AtomicInteger();
        server.createContext("/v1/models",exchange->{exchange.getResponseHeaders().add("Location",base+"/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{forwarded.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        assertThatThrownBy(()->discovery.fetch(base,"test-secret","compatible")).hasMessageNotContaining("test-secret");
        assertThat(forwarded.get()).isZero();
    }
    @Test void emptyAndMalformedListsRemainDistinct() {
        server.createContext("/v1/models",exchange->{byte[] body="{\"data\":[]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        assertThat(discovery.fetch(base,"test-secret","compatible")).isEmpty();
        server.removeContext("/v1/models");
        server.createContext("/v1/models",exchange->{byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        assertThatThrownBy(()->discovery.fetch(base,"test-secret","compatible")).hasMessageContaining("标准模型列表");
    }
}
