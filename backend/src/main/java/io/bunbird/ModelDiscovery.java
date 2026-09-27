package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Same-origin model discovery. Never follow redirects or expose upstream error bodies. */
final class ModelDiscovery {
    private final ObjectMapper json;
    ModelDiscovery(ObjectMapper json) { this.json=json; }
    static String normalize(String base) {
        String value=Objects.toString(base,"").trim().replaceAll("/+$","");
        try {
            URI uri=URI.create(value);
            if(value.length()>1024 || !Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new ResponseStatusException(BAD_REQUEST,"接口地址无效"); }
    }
    static List<String> endpoints(String base) {
        base=normalize(base);
        if(URI.create(base).getHost().equals("api.deepseek.com"))return List.of(URI.create(base).resolve("/models").toString());
        var urls=new LinkedHashSet<String>();
        urls.add(base+(base.matches(".*/v[0-9]+$")?"/models":"/v1/models"));
        urls.add(base+"/models");
        for(String suffix:List.of("/api/anthropic","/apps/anthropic","/anthropic","/claudecode")) {
            if(base.endsWith(suffix)) {
                String root=base.substring(0,base.length()-suffix.length());
                urls.add(root+"/v1/models");urls.add(root+"/models");break;
            }
        }
        return List.copyOf(urls);
    }
    List<String> fetch(String base,String key,String mode) {
        if(key==null || key.isBlank() || key.length()>8192 || key.chars().anyMatch(Character::isISOControl)
            || !Set.of("anthropic","compatible").contains(Objects.toString(mode,"")))
            throw new ResponseStatusException(BAD_REQUEST,"请检查协议和 API 密钥");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            for(String endpoint:endpoints(base)) {
                var builder=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(6))
                    .header("Authorization","Bearer "+key).header("Accept","application/json");
                if(mode.equals("anthropic"))builder.header("x-api-key",key).header("anthropic-version","2023-06-01");
                var response=client.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());
                int status=response.statusCode();
                if(status==404 || status==405)continue;
                if(status==401 || status==403)throw new ResponseStatusException(BAD_GATEWAY,"渠道认证失败，请检查密钥及模型权限");
                if(status==429)throw new ResponseStatusException(BAD_GATEWAY,"渠道暂时限流，请稍后重试");
                if(status!=200)throw new ResponseStatusException(BAD_GATEWAY,"渠道模型列表请求失败，可手动填写模型 ID");
                if(response.body().length()>1_000_000)throw new ResponseStatusException(BAD_GATEWAY,"模型列表响应过大");
                var root=json.readTree(response.body());
                var data=root.path("data");
                if(!data.isArray())throw new ResponseStatusException(BAD_GATEWAY,"渠道未返回标准模型列表，可手动填写模型 ID");
                var models=new TreeSet<String>();
                for(var item:data) {
                    String id=item.path("id").asText("");
                    if(!id.isBlank() && id.length()<=128 && id.chars().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))
                        && !id.contains(key))models.add(id);
                    if(models.size()>=1000)break;
                }
                return List.copyOf(models);
            }
            throw new ResponseStatusException(BAD_GATEWAY,"渠道未提供模型列表接口，请手动填写模型 ID");
        } catch(ResponseStatusException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ResponseStatusException(BAD_GATEWAY,"模型查询已中断"); }
        catch(Exception e) { throw new ResponseStatusException(BAD_GATEWAY,"无法读取模型列表，请检查接口地址或稍后重试"); }
    }
}
