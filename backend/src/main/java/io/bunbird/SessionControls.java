package io.bunbird;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Topic/user preferences and factual completion summaries; no remote calls here. */
final class SessionControls {
    static final Set<String> STYLES=Set.of("concise","detailed","professional","friendly");
    static boolean supportsEffort(String model) {
        return model.toLowerCase(Locale.ROOT).matches("claude-(opus-(4-[678]|5(-5)?)|sonnet-(4-6|5(-5)?)|haiku-5-5|fable-5(-1)?)(-\\d{8})?");
    }
    static List<String> effortLevels(String model) {
        if(!supportsEffort(model))return List.of("auto");
        return model.contains("4-6")?List.of("auto","low","medium","high","max"):List.of("auto","low","medium","high","xhigh","max");
    }
    static Map<String,Object> settings(JdbcTemplate db,String topic,String sender) {
        var rows=db.queryForList("SELECT effort,style FROM session_settings WHERE topic_id=? AND sender_id=?",topic,sender);
        return rows.isEmpty()?Map.of("effort","auto","style","concise"):rows.getFirst();
    }
    static Map<String,Object> summary(JdbcTemplate db,String id) {
        var row=db.queryForMap("SELECT * FROM task WHERE id=?",id);
        var aggregate=new long[4];int turns=0,reported=0,toolReported=0;
        for(var t:db.queryForList("SELECT usage_json,status FROM task WHERE topic_id=? AND sender_id=? AND position<=?",row.get("topic_id"),row.get("sender_id"),row.get("position"))) {
            if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(t.get("status")))turns++;
            var tools=toolCalls(t.get("usage_json"));if(tools!=null){aggregate[3]+=tools;toolReported++;}
            long[] usage=usage(t.get("usage_json"));if(usage!=null){reported++;for(int i=0;i<3;i++)aggregate[i]+=usage[i];}
        }
        Long start=db.queryForObject("SELECT MIN(created_at) FROM run_event WHERE task_id=? AND type='RUNNING'",Long.class,id);
        long[] usage=usage(row.get("usage_json"));
        var out=new LinkedHashMap<String,Object>();
        out.put("executor",row.get("executor_kind"));out.put("taskId",id);out.put("model",Objects.toString(row.get("model"),"未记录模型"));
        out.put("effort",row.get("effort"));out.put("style",row.get("reply_style"));out.put("status",row.get("status"));
        out.put("elapsedMs",start==null?null:Math.max(0,((Number)row.get("updated_at")).longValue()-start));
        out.put("tokens",usage==null?null:usage[2]);out.put("inputTokens",usage==null?null:usage[0]);out.put("outputTokens",usage==null?null:usage[1]);
        out.put("turns",turns);out.put("reportedTurns",reported);out.put("sessionTokens",reported==0?null:aggregate[2]);
        out.put("toolCalls",toolReported>0?aggregate[3]:null);
        long delegated=db.queryForObject("SELECT COALESCE(SUM(total_tokens),0) FROM delegation WHERE task_id=?",Long.class,id);
        out.put("delegatedTokens",delegated);out.put("next",settings(db,(String)row.get("topic_id"),(String)row.get("sender_id")));
        return out;
    }
    private static Long toolCalls(Object raw) {
        try {
            var node=new ObjectMapper().readTree(Objects.toString(raw,"{}"));
            return node!=null && node.path("bunbird_tool_calls").isIntegralNumber()?Math.max(0,node.path("bunbird_tool_calls").asLong()):null;
        }catch(Exception ignored){return null;}
    }
    private static long[] usage(Object raw) {
        try {
            var n=new ObjectMapper().readTree(Objects.toString(raw,"{}"));
            if(n==null || !n.isObject())return null;
            boolean anthropic=n.has("input_tokens")||n.has("output_tokens");
            if(!anthropic&&!n.has("prompt_tokens")&&!n.has("completion_tokens"))return null;
            long in=Math.max(0,n.path(anthropic?"input_tokens":"prompt_tokens").asLong());
            long output=Math.max(0,n.path(anthropic?"output_tokens":"completion_tokens").asLong());
            long cache=anthropic?Math.max(0,n.path("cache_read_input_tokens").asLong())+Math.max(0,n.path("cache_creation_input_tokens").asLong()):0;
            return new long[]{in+cache,output,in+cache+output,Math.max(0,n.path("bunbird_tool_calls").asLong())};
        }catch(Exception ignored){return null;}
    }
    static Map<String,Object> action(JdbcTemplate db,Map<String,Object> task,Api.SessionAction action,List<AccountPool.ModelOption> models) {
        String topic=(String)task.get("topic_id"),sender=(String)task.get("sender_id");
        var current=settings(db,topic,sender);String effort=(String)current.get("effort"),style=(String)current.get("style");
        String selectedModel="账号池自动选择";
        var preference=db.queryForList("SELECT model FROM session_model WHERE topic_id=? AND sender_id=?",topic,sender);
        if(!preference.isEmpty())selectedModel=(String)preference.getFirst().get("model");
        boolean claude="claude".equals(task.get("executor_kind"));
        String message="设置仅影响你在当前话题的后续消息。";
        String option=Objects.toString(action.option(),"");
        switch(action.action()) {
            case "set_model" -> {
                if(!option.equals("auto")) {
                    var match=models.stream().filter(m->m.selector().equals(option)).findFirst().orElseThrow(()->new ResponseStatusException(BAD_REQUEST,"Model unavailable"));
                    db.update("DELETE FROM session_model WHERE topic_id=? AND sender_id=?",topic,sender);
                    db.update("INSERT INTO session_model(topic_id,sender_id,account_id,model) VALUES(?,?,?,?)",topic,sender,match.accountId(),match.model());
                } else db.update("DELETE FROM session_model WHERE topic_id=? AND sender_id=?",topic,sender);
                selectedModel=option.equals("auto")?"账号池自动选择":models.stream().filter(m->m.selector().equals(option)).findFirst().orElseThrow().model();
                effort="auto";message="模型已切换，强度恢复自动；下条消息生效。";
            }
            case "set_effort" -> {
                if((!claude&&!option.equals("auto")) || !effortLevels(selectedModel).contains(option) || (!option.equals("auto")&&!supportsEffort(selectedModel)))throw new ResponseStatusException(BAD_REQUEST,"Effort unsupported");
                effort=option;message="强度已设置，下条消息生效。";
            }
            case "set_style" -> {
                if(!STYLES.contains(option))throw new ResponseStatusException(BAD_REQUEST,"Invalid style");
                style=option;message="回复风格已设置，下条消息生效。";
            }
            default -> {}
        }
        if(action.action().startsWith("set_")) {
            db.update("DELETE FROM session_settings WHERE topic_id=? AND sender_id=?",topic,sender);
            db.update("INSERT INTO session_settings(topic_id,sender_id,effort,style) VALUES(?,?,?,?)",topic,sender,effort,style);
        }
        var out=new LinkedHashMap<String,Object>();out.put("message",message);out.put("summary",summary(db,(String)task.get("id")));
        out.put("panel",action.action().replace("set_",""));out.put("supportsEffort",claude&&supportsEffort(selectedModel));out.put("selectedModel",selectedModel);
        out.put("effortLevels",claude?effortLevels(selectedModel):List.of("auto"));
        out.put("models",models.stream().limit(99).map(m->Map.of("label",m.accountName()+" / "+m.model(),"value",m.selector())).toList());
        return out;
    }
}
