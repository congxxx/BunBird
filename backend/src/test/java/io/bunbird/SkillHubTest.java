package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class SkillHubTest {
    @TempDir Path temp;
    JdbcTemplate db;
    SkillHub hub;
    @BeforeEach void init() throws Exception {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute(Files.readString(Path.of("src/main/resources/db/migration/V11__skill_hub.sql")));
        db.execute("ALTER TABLE managed_skill ADD COLUMN tenant_id VARCHAR DEFAULT ''");
        hub=new SkillHub(db,new ObjectMapper());
        Files.writeString(temp.resolve("SKILL.md"),"# A test skill\nRead scripts/helper.py when needed.");
        Files.createDirectories(temp.resolve("scripts"));Files.writeString(temp.resolve("scripts/helper.py"),"print('not executed by import')");
    }
    @Test void importSnapshotsFilesAndTogglePersistsAcrossServiceInstances() throws Exception {
        Files.writeString(temp.resolve(".env"),"private-value");
        var saved=hub.importSkill(new SkillHub.Import("local",temp.toString(),"","Test",true));
        String id=(String)saved.get("id");
        assertThat(hub.list()).hasSize(1);
        assertThat(hub.list().getFirst()).doesNotContainKey("files_json").doesNotContainKey("instructions");
        assertThat(hub.bundles().toString()).contains("scripts/helper.py").doesNotContain(".env","private-value");
        Files.writeString(temp.resolve("SKILL.md"),"changed at source");
        assertThat(hub.detail(id).get("instructions")).isEqualTo("# A test skill\nRead scripts/helper.py when needed.");
        hub.enabled(id,false);
        var restarted=new SkillHub(db,new ObjectMapper());
        assertThat(restarted.bundles()).isEmpty();
        restarted.enabled(id,true);assertThat(restarted.bundles()).hasSize(1);
        assertThat(restarted.detail(id).get("revision")).isEqualTo(saved.get("revision"));
    }
    @Test void rejectsMissingSkillAndOversizedBundleWithoutPersisting() throws Exception {
        Files.delete(temp.resolve("SKILL.md"));
        assertThatThrownBy(()->hub.importSkill(new SkillHub.Import("local",temp.toString(),"","Test",true))).hasMessageContaining("SKILL.md");
        Files.writeString(temp.resolve("SKILL.md"),"valid");Files.write(temp.resolve("large.bin"),new byte[SkillHub.MAX_BYTES+1]);
        assertThatThrownBy(()->hub.importSkill(new SkillHub.Import("local",temp.toString(),"","Test",true))).hasMessageContaining("1 MB");
        assertThat(hub.list()).isEmpty();
    }
    @Test void rejectsUnsafeGitSourcesBeforeStartingGit() {
        for(String url:List.of("file:///tmp/repo","https://token@example.com/repo","https://example.com/repo?token=secret","--upload-pack=evil"))
            assertThatThrownBy(()->hub.importSkill(new SkillHub.Import("git",url,"","Test",true))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->hub.importSkill(new SkillHub.Import("git","https://example.com/repo","../outside","Test",true))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(hub.list()).isEmpty();
    }
}
