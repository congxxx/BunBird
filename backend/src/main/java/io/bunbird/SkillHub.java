package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Imports immutable skill snapshots. Importing never executes skill scripts. */
@Service
public class SkillHub {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    static final int MAX_BYTES=1_048_576, MAX_FILES=100;
    public SkillHub(JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }
    @org.springframework.beans.factory.annotation.Value("${bunbird.tenant:}") private String defaultTenant="";
    private String tenant() { return TenantContext.tenant(defaultTenant); }
    public record Import(String type,String source,String directory,String name,boolean enabled) {
        @Override public String toString() { return "SkillImport[redacted]"; }
    }
    public List<Map<String,Object>> list() {
        return db.queryForList("SELECT id,name,source_type,source,revision,enabled,created_at FROM managed_skill WHERE tenant_id=? ORDER BY created_at,id",tenant());
    }
    public Map<String,Object> detail(String id) {
        var rows=db.queryForList("SELECT id,name,source_type,source,revision,enabled,instructions,created_at FROM managed_skill WHERE id=? AND tenant_id=?",id,tenant());
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"技能不存在");
        return rows.getFirst();
    }
    public void enabled(String id,boolean enabled) {
        if(db.update("UPDATE managed_skill SET enabled=? WHERE id=? AND tenant_id=?",enabled,id,tenant())!=1)throw new ResponseStatusException(NOT_FOUND,"技能不存在");
    }
    public List<Map<String,Object>> bundles() {
        return db.queryForList("SELECT id,name,revision,files_json FROM managed_skill WHERE enabled=TRUE AND tenant_id=? ORDER BY created_at,id",tenant()).stream().map(row->{
            try { return Map.<String,Object>of("id",row.get("id"),"name",row.get("name"),"revision",row.get("revision"),"files",json.readTree((String)row.get("files_json"))); }
            catch(Exception e) { throw new IllegalStateException("Invalid skill snapshot"); }
        }).toList();
    }
    public synchronized Map<String,Object> importSkill(Import request) {
        Path temporary=null;
        try {
            if(db.queryForObject("SELECT COUNT(*) FROM managed_skill WHERE tenant_id=?",Integer.class,tenant())>=20)throw new ResponseStatusException(CONFLICT,"最多支持 20 个导入技能");
            String source=Objects.toString(request.source(),"").trim();
            String directory=Objects.toString(request.directory(),"").trim().replace('\\','/');
            if(source.isEmpty() || source.length()>2048 || source.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            Path root;
            String revision="";
            if("local".equals(request.type())) {
                root=Path.of(source);
                if(!root.isAbsolute())throw new ResponseStatusException(BAD_REQUEST,"请输入服务器上的绝对目录路径");
                if(!directory.isEmpty())throw new ResponseStatusException(BAD_REQUEST,"本地导入请直接填写技能目录路径");
            } else if("git".equals(request.type())) {
                URI uri=URI.create(source);
                if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                    throw new ResponseStatusException(BAD_REQUEST,"Git 来源请使用不含凭据的 HTTPS 仓库地址");
                if(directory.startsWith("/") || directory.contains(":") || Arrays.asList(directory.split("/")).contains(".."))throw new IllegalArgumentException();
                temporary=Files.createTempDirectory("bunbird-skill-");
                Path repo=temporary.resolve("repo");
                git(temporary,"clone","--depth","1","--single-branch","--",source,repo.toString());
                revision=git(repo,"rev-parse","HEAD").trim();
                root=repo.resolve(directory).normalize();
                if(!root.startsWith(repo))throw new IllegalArgumentException();
            } else throw new IllegalArgumentException();
            var files=readFiles(root);
            byte[] skill=Base64.getDecoder().decode(files.getOrDefault("SKILL.md",""));
            if(skill.length==0 || skill.length>65536)throw new ResponseStatusException(BAD_REQUEST,"技能目录必须包含非空且不超过 64 KB 的 SKILL.md");
            String instructions=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(skill)).toString();
            if(instructions.isBlank())throw new IllegalArgumentException();
            String name=Objects.toString(request.name(),"").trim();
            if(name.isEmpty())name=root.getFileName().toString();
            if(name.length()>80 || name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
            String serialized=json.writeValueAsString(files);
            if(revision.isEmpty())revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(StandardCharsets.UTF_8)));
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO managed_skill(id,name,source_type,source,revision,enabled,files_json,instructions,created_at,tenant_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,name,request.type(),source+(directory.isEmpty()?"":" # "+directory),revision,request.enabled(),serialized,instructions,System.currentTimeMillis(),tenant());
            return detail(id);
        } catch(ResponseStatusException e) { throw e; }
        catch(Exception e) { throw new ResponseStatusException(BAD_REQUEST,"导入失败：请检查目录、SKILL.md、UTF-8 编码和仓库访问权限；最多 100 个文件、合计 1 MB"); }
        finally {
            if(temporary!=null)try(var paths=Files.walk(temporary)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            } catch(Exception ignored) {}
        }
    }
    static Map<String,String> readFiles(Path root) throws Exception {
        root=root.toAbsolutePath().normalize();
        if(!Files.isDirectory(root) || Files.isSymbolicLink(root))throw new IllegalArgumentException();
        final Path base=root, real=root.toRealPath();
        var files=new TreeMap<String,String>();
        int[] bytes={0};
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),10,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs) throws java.io.IOException {
                if(!path.equals(base) && path.getFileName().toString().startsWith("."))return FileVisitResult.SKIP_SUBTREE;
                if(!path.toRealPath().startsWith(real))throw new java.io.IOException("Linked directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs) throws java.io.IOException {
                if(path.getFileName().toString().startsWith("."))return FileVisitResult.CONTINUE;
                if(!attrs.isRegularFile() || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(real))throw new java.io.IOException("Unsupported file");
                byte[] content;
                try(var input=Files.newInputStream(path)) { content=input.readNBytes(MAX_BYTES-bytes[0]+1); }
                bytes[0]+=content.length;
                if(bytes[0]>MAX_BYTES || files.size()>=MAX_FILES)throw new java.io.IOException("Skill too large");
                String name=base.relativize(path).toString().replace('\\','/');
                if(name.contains(":") || name.length()>240)throw new java.io.IOException("Invalid path");
                files.put(name,Base64.getEncoder().encodeToString(content));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
    private String git(Path cwd,String... args) throws Exception {
        var command=new ArrayList<>(List.of("git","-c","core.hooksPath="+cwd.resolve("disabled-hooks"),"-c","credential.helper=","-c","protocol.file.allow=never"));
        command.addAll(List.of(args));
        Path output=Files.createTempFile("bunbird-skill-git-",".log");
        Process process=null;
        try {
            var builder=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            var env=builder.environment();
            env.keySet().removeIf(k->k.startsWith("GIT_") || k.contains("TOKEN") || k.contains("SECRET") || k.contains("API_KEY"));
            env.put("GIT_TERMINAL_PROMPT","0");env.put("GIT_CONFIG_NOSYSTEM","1");env.put("GIT_CONFIG_GLOBAL",System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null");
            process=builder.start();
            if(!process.waitFor(60,TimeUnit.SECONDS))throw new ResponseStatusException(BAD_GATEWAY,"Git 拉取超时");
            if(process.exitValue()!=0)throw new ResponseStatusException(BAD_REQUEST,"Git 拉取失败，请检查仓库地址和网络；当前支持公开 HTTPS 仓库");
            return args[0].equals("rev-parse")?Files.readString(output):"";
        } finally {
            if(process!=null && process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            Files.deleteIfExists(output);
        }
    }
}
