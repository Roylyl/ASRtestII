package org.hotwordtest.cloudprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.hotwordtest.CloudChatClient;
import org.hotwordtest.CloudSettings;
import org.hotwordtest.OfflineVoiceRecluster;
import org.hotwordtest.SpeakerDecisionConfig;
import org.hotwordtest.SpeakerDefaults;
import org.hotwordtest.SpeakerModels;
import org.hotwordtest.TranscriptReview;
import org.hotwordtest.WavReader;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public final class CloudProbe extends Instrumentation {
    private static final String CLOUD_OUTPUT = "cloud-probe-result.json";
    private static final String REGROUP_OUTPUT = "local-regroup-result.json";
    private static final String SETTINGS_OUTPUT = "cloud-device-settings-result.json";
    private static final String REAL_OUTPUT = "cloud-deepseek-live-result.json";
    private static final String LIVE_MODEL = "deepseek-flash";
    private static final String MODEL = "cloud-probe-model";
    private static final String EXPECTED = "双方讨论了测试服务部署，预算3万元，周五核对结果。";
    private Bundle options;

    @Override public void onCreate(Bundle arguments) {
        options = arguments == null ? Bundle.EMPTY : arguments;
        super.onCreate(arguments);
        start();
    }

    private static void save(File out, JSONObject state) throws Exception {
        AtomicFile file = new AtomicFile(out);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException e) {
            file.failWrite(stream);
            throw e;
        }
    }

    private static String read(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return !json.isEmpty() && json.charAt(0)=='\uFEFF' ? json.substring(1) : json;
    }

    private static String localName(String value, String extension) {
        if (value == null || !value.endsWith(extension) || !new File(value).getName().equals(value))
            throw new IllegalArgumentException("必须指定目标目录中的"+extension+"文件名");
        return value;
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private void checkpoint(File out, JSONObject state, String phase) throws Exception {
        state.put("phase", phase).put("phase_started_elapsed_ms", SystemClock.elapsedRealtime());
        save(out, state);
        Bundle progress = new Bundle();
        progress.putString("stream", phase+"\n");
        sendStatus(1, progress);
    }

    private static List<TranscriptReview.Row> syntheticRows() {
        String source = "仅本机的合成来源.wav";
        return Arrays.asList(
             new TranscriptReview.Row(source, "[00:01.00–00:03.00]说话人1：", "请部暑测试服务，预算3万元。",
                      new TranscriptReview.VoiceEvidence("synthetic-local-voice",true,0,1000,3000)),
             new TranscriptReview.Row(source, "[00:04.00–00:06.00]说话人2：", "我会在周五核对测试结果。"));
    }

    private static void inspectRetiredCache(Context target, JSONObject state) throws Exception {
        Path root = new File(target.getNoBackupFilesDir(), "qwen").toPath();
        boolean absent = Files.notExists(root, LinkOption.NOFOLLOW_LINKS);
        int gguf = 0, tmp = 0, part = 0;
        if (!absent) {
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "旧模型路径不是可检查的目录");
            // Files.walk does not follow symbolic links. Inspect names only.
            try (Stream<Path> listing = Files.walk(root)) {
                Iterator<Path> paths = listing.iterator();
                while (paths.hasNext()) {
                    Path item = paths.next();
                    if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) continue;
                    String name = item.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".gguf")) gguf++;
                    else if (name.endsWith(".tmp")) tmp++;
                    else if (name.endsWith(".part")) part++;
                }
            }
        }
        state.put("qwen_directory_exists", !absent).put("gguf_files", gguf).put("tmp_files", tmp)
                .put("part_files", part).put("retired_files_total", gguf + tmp + part);
    }

    private void deviceSettings(Context target, File out, JSONObject state) throws Exception {
        state.put("status","running").put("mode","device_settings").put("http_calls",0);
        checkpoint(out,state,"isolated_settings_roundtrip");
        final String name = "cloud_probe_keystore_"+UUID.randomUUID();
        Context scoped = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String requested, int mode) {
                check("cloud_asr_v1".equals(requested) && mode==MODE_PRIVATE,"云设置未使用预期的私有存储");
                return target.getSharedPreferences(name, MODE_PRIVATE);
            }
        };
        String fake = "synthetic-key-"+UUID.randomUUID();
        String endpoint = "http://127.0.0.1:18765/v1";
        String normalized = endpoint+"/chat/completions";
        File prefFile = new File(new File(target.getDataDir(),"shared_prefs"),name+".xml");
        try {
            CloudSettings first = new CloudSettings(scoped);
            check(!first.summary().getHasApiKey(),"隔离偏好中不应有既存密钥");
            first.save(endpoint,MODEL,fake);
            SharedPreferences prefs = scoped.getSharedPreferences("cloud_asr_v1", Context.MODE_PRIVATE);
            String stored = prefs.getString("api_key",null);
            check(stored!=null && !stored.isEmpty() && !stored.contains(fake),"API Key未加密");
            String xml = read(prefFile);
            check(!xml.contains(fake) && xml.contains(stored),"偏好XML未持久化预期的密文");
            CloudSettings restored = new CloudSettings(scoped);
            CloudSettings.Summary summary = restored.summary();
            CloudSettings.RequestConfig cfg = restored.requestConfig$v4_asr();
            check(summary.getHasApiKey() && normalized.equals(summary.getEndpoint()) && MODEL.equals(summary.getModel())
                    && fake.equals(cfg.getApiKey()) && normalized.equals(cfg.getEndpoint()) && MODEL.equals(cfg.getModel()),
                    "重建CloudSettings后未读回相同的隔离测试配置");
            state.put("ciphertext_persisted",true).put("plaintext_absent",true).put("rebuilt_settings_equal",true);
        } finally {
            // Only this run's isolated preference file is removed. CloudSettings' keystore alias belongs to the app.
            target.deleteSharedPreferences(name);
            boolean removed=!prefFile.exists() && !new File(prefFile.getPath()+".bak").exists();
            state.put("temporary_prefs_removed",removed);
            check(removed,"临时云配置未清理");
        }
        checkpoint(out,state,"inspect_retired_cache");
        inspectRetiredCache(target,state);
    }

    private static void checkPayload(String material, int stage, List<TranscriptReview.Row> fixture) {
        checkPayload(material,stage,fixture,true);
    }

    private static void checkPayload(String material, int stage, List<TranscriptReview.Row> fixture,
                                     boolean summaryUsesMockCorrection) {
        try {
            JSONArray payload = new JSONArray(material);
            check(payload.length()==fixture.size(),"测试请求的行数不符");
            for(int i=0;i<fixture.size();i++) {
                JSONObject obj=payload.getJSONObject(i);
                check(obj.length()==(stage==1?3:2) && obj.has("row")==(stage==1),"请求有非必要字段");
                if(stage==1)check(obj.getInt("row")==i+1,"勘误行号不符");
                check(obj.getString("speaker").equals("说话人"+(i+1)), "说话人不符");
                String expected=fixture.get(i).getSpeech();
                if(stage==2 && summaryUsesMockCorrection && i==0)expected=expected.replace("部暑","部署");
                check(obj.getString("text").equals(expected),"请求不是预期的合成文字");
            }
        }catch(org.json.JSONException e){throw new IllegalStateException("合成测试请求格式无效",e);}
    }

    private static void checkLiveAddress(String address) {
        URI uri = URI.create(address);
        check("https".equalsIgnoreCase(uri.getScheme()) && "api.deepseek.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort()==-1 || uri.getPort()==443) && uri.getRawUserInfo()==null
                && uri.getRawQuery()==null && uri.getRawFragment()==null
                && ("/chat/completions".equals(uri.getRawPath()) || "/v1/chat/completions".equals(uri.getRawPath())),
                "需要官方标准端点");
    }

    // Never publish arbitrary exception messages from live settings, TLS, the server or JSON parsing.
    private static String liveError(Throwable error) {
        String message = error.getMessage();
        if (message==null) return "测试未完成";
        for (String safe : Arrays.asList("需要官方标准端点","需要指定的测试模型","请先在应用设置中填写API Key",
                "无法读取API Key，请重新设置","云端认证失败，请检查API Key和模型访问权限",
                "云端接口不存在，请检查API地址","云端请求被限流或配额不足","云端请求超时",
                "云端请求超时，请检查网络或重试","云端连接失败，请检查网络和API地址",
                "云端地址发生重定向，为保护API Key已拒绝，请填写最终接口地址",
                "云服务未返回完整的勘误JSON，未采用本次修改。","未完成两项云端测试")) {
            if (safe.equals(message)) return safe;
        }
        if (message.matches("云端服务暂不可用（HTTP [0-9]{3}）")
                || message.matches("云端请求失败（HTTP [0-9]{3}），请检查地址和模型配置")) return message;
        return "测试未完成";
    }

    private boolean runLiveStage(File out, JSONObject state, CloudChatClient client, String key,
                                 List<TranscriptReview.Row> rows, int stage) throws Exception {
        String name = stage==1 ? "correct" : "summarize";
        JSONObject report = new JSONObject();
        state.put(name,report);
        checkpoint(out,state,name);
        long started = SystemClock.elapsedRealtime();
        Function0<Boolean> cancelled = () -> Thread.currentThread().isInterrupted();
        TranscriptReview.Completion completion = (system,user,stop) -> {
            check(!report.has("requested"),"请求次数超出限制");
            checkPayload(user,stage,rows,false);
            try {
                report.put("requested",true);
                CloudChatClient.Result reply = client.complete(system,user,0,stop);
                // Only the provider's final text enters TranscriptReview; no response body is logged.
                check(!reply.getText().contains(key) && !reply.getResponseModel().contains(key),"返回值含受限内容");
                report.put("response_model",reply.getResponseModel().isEmpty()?JSONObject.NULL:reply.getResponseModel())
                        .put("prompt_tokens",reply.getPromptTokens()==null?JSONObject.NULL:reply.getPromptTokens())
                        .put("completion_tokens",reply.getCompletionTokens()==null?JSONObject.NULL:reply.getCompletionTokens())
                        .put("reasoning_content_present",reply.getReasoningContentPresent());
                return reply.getText();
            } catch (org.json.JSONException e) {
                throw new IllegalStateException("写入测试指标失败");
            }
        };
        try {
            if (stage==1) {
                TranscriptReview.Edited edited = TranscriptReview.INSTANCE.correct(completion,rows,cancelled,(i,n)->Unit.INSTANCE);
                report.put("text",TranscriptReview.INSTANCE.render(edited.getRows())).put("note",edited.getWarning());
                rows.clear();
                rows.addAll(edited.getRows());
            } else {
                report.put("text",TranscriptReview.INSTANCE.summarize(completion,rows,cancelled,label->Unit.INSTANCE));
            }
            report.put("status","completed");
            return true;
        } catch (RuntimeException e) {
            report.put("status","failed").put("error",liveError(e));
            return false;
        } finally {
            report.put("elapsed_ms",SystemClock.elapsedRealtime()-started);
            save(out,state);
        }
    }

    private void liveDeepSeek(Context target, File out, JSONObject state) throws Exception {
        List<TranscriptReview.Row> rows = new java.util.ArrayList<>(syntheticRows());
        state.put("status","running").put("mode","deepseek_live").put("requested_model",LIVE_MODEL)
                .put("original",TranscriptReview.INSTANCE.render(rows));
        checkpoint(out,state,"check_saved_settings");
        CloudSettings settings = new CloudSettings(target);
        CloudSettings.Summary summary = settings.summary();
        checkLiveAddress(summary.getEndpoint());
        check(LIVE_MODEL.equals(summary.getModel()),"需要指定的测试模型");
        check(summary.getHasApiKey(),"请先在应用设置中填写API Key");
        CloudSettings.RequestConfig config = settings.requestConfig$v4_asr();
        checkLiveAddress(config.getEndpoint());
        check(LIVE_MODEL.equals(config.getModel()),"需要指定的测试模型");
        CloudChatClient client = new CloudChatClient(config,url -> {
            checkLiveAddress(url.toExternalForm());
            try { return (HttpURLConnection)url.openConnection(Proxy.NO_PROXY); }
            catch (IOException e) { throw new IllegalStateException("云端连接失败，请检查网络和API地址"); }
        },15000,120000);
        boolean corrected = runLiveStage(out,state,client,config.getApiKey(),rows,1);
        boolean summarized = corrected && runLiveStage(out,state,client,config.getApiKey(),rows,2);
        check(corrected && summarized,"未完成两项云端测试");
    }

    private void cloudOnly(Context target, File out, JSONObject state) throws Exception {
        int port = Integer.parseInt(options.getString("mockPort","18765"));
        check(port>0 && port<=65535,"mockPort无效");
        final String endpoint="http://127.0.0.1:"+port+"/v1";
        String prefName="cloud_probe_synthetic_"+UUID.randomUUID();
        Context testContext=new ContextWrapper(target);
        SharedPreferences prefs=testContext.getSharedPreferences(prefName,Context.MODE_PRIVATE);
        try {
            check(prefs.edit().putString("endpoint",endpoint).putString("model",MODEL)
                    .putString("api_key","cloud-probe-fake-key").commit(),"写入隔离测试配置失败");
            // Use only temporary, synthetic settings. Do not access production preferences or its keystore alias.
            CloudSettings.RequestConfig config=new CloudSettings.RequestConfig(prefs.getString("endpoint",null),
                    prefs.getString("model",null),prefs.getString("api_key",null));
            AtomicInteger connections=new AtomicInteger(), stages=new AtomicInteger();
            CloudChatClient client=new CloudChatClient(config, url->{
                check(url.getProtocol().equals("http") && url.getHost().equals("127.0.0.1") && url.getPort()==port
                        && "/v1/chat/completions".equals(url.getPath()) && url.getQuery()==null,"只能访问指定的本机测试端点");
                connections.incrementAndGet();
                try{return (HttpURLConnection)url.openConnection(Proxy.NO_PROXY);}
                catch(IOException error){throw new IllegalStateException("无法建立本机测试连接",error);}
            },15000,30000);
            List<TranscriptReview.Row> rows=syntheticRows();
            TranscriptReview.Completion completion=(system,user,cancelled)->{
                int stage=stages.incrementAndGet();
                check(stage<=2,"出现额外的云请求");
                checkPayload(user,stage,rows);
                return client.complete(system,user,0,cancelled).getText();
            };
            Function0<Boolean> cancelled=()->Thread.currentThread().isInterrupted();
            state.put("status","running").put("mode","synthetic_loopback").put("model",MODEL)
                 .put("original",TranscriptReview.INSTANCE.render(rows));
            checkpoint(out,state,"correct");
            TranscriptReview.Edited edited=TranscriptReview.INSTANCE.correct(completion,rows,cancelled,(i,n)->Unit.INSTANCE);
            for(int i=0;i<rows.size();i++){
                TranscriptReview.Row original=rows.get(i), revised=edited.getRows().get(i);
                check(original.getSource().equals(revised.getSource()) && original.getPrefix().equals(revised.getPrefix())
                        && original.getVoice()==revised.getVoice(),"勘误改变了行的归属或时间");
            }
            check(edited.getRows().get(0).getSpeech().equals("请部署测试服务，预算3万元。") && edited.getRows().get(1).equals(rows.get(1)),
                    "合成错词未正确回填");
            state.put("corrected",TranscriptReview.INSTANCE.render(edited.getRows())).put("note",edited.getWarning());
            checkpoint(out,state,"summarize");
            String summary=TranscriptReview.INSTANCE.summarize(completion,edited.getRows(),cancelled,label->Unit.INSTANCE);
            check(summary.equals("【"+rows.get(0).getSource()+"】\n"+EXPECTED),"未得到预期的独立摘要");
            check(stages.get()==2 && connections.get()==2,"应只发出勘误、摘要两个HTTP请求");
            state.put("summary",summary).put("http_calls",connections.get()).put("row_metadata_preserved",true)
                    .put("model_for_both_calls",MODEL);
        }finally{
            target.deleteSharedPreferences(prefName);
        }
    }

    private static double fraction(SharedPreferences prefs, String key, int defaultPercent) {
        return Math.max(0, Math.min(100, prefs.getInt(key, defaultPercent))) / 100.0;
    }

    private static void assertSameField(JSONObject original, JSONObject revised, String key) throws IOException {
        Object left = original.opt(key), right = revised.opt(key);
        boolean equal = left instanceof JSONObject || left instanceof JSONArray
                ? right != null && left.toString().equals(right.toString()) : java.util.Objects.equals(left, right);
        if (original.has(key) != revised.has(key) || !equal) throw new IOException("重分组改变了原始字段：" + key);
    }

    private static int verifyRegroupBoundaries(JSONObject original, JSONObject revised) throws Exception {
        for (String key : new String[]{"file","status","file_order","sample_rate","duration_ms","input_samples",
                "merged_offset_samples","merged_offset_ms","asr_segments","diarization"}) {
            assertSameField(original, revised, key);
        }
        JSONArray before = original.getJSONArray("speaker_turn_transcripts");
        JSONArray after = revised.getJSONArray("speaker_turn_transcripts");
        if (after.length() != before.length()) throw new IOException("重分组改变了原始区间数");
        for (int i = 0; i < before.length(); i++) {
            JSONObject a = before.getJSONObject(i), b = after.getJSONObject(i);
            for (String key : new String[]{"start_sample","end_sample","start_ms","end_ms","text","speaker_candidates",
                    "wearer_identity","voice_group_candidate"}) {
                assertSameField(a,b,key);
            }
        }
        return before.length();
    }

    private void regroupOnly(Context target, File dir, File out, JSONObject state) throws Exception {
        String inputName = localName(options.getString("inputFixture", "batch-result.json"), ".json");
        if (REGROUP_OUTPUT.equals(inputName)) throw new IllegalArgumentException("不能把探针输出当作输入覆盖");
        String wavName = options.getString("audioFixture", "source-00-20.wav");
        if (wavName == null || !wavName.toLowerCase(java.util.Locale.ROOT).endsWith(".wav") || !new File(wavName).getName().equals(wavName))
            throw new IllegalArgumentException("audioFixture应是目标目录中的单个WAV文件名");
        state.put("status","running").put("mode","regroup_only").put("input_file",inputName)
                .put("audio_fixture",wavName).put("asr_reran",false).put("diarization_reran",false);
        checkpoint(out,state,"read_regroup_fixture");
        JSONObject envelope = new JSONObject(read(new File(dir,inputName)));
        JSONArray inputs = envelope.getJSONArray("results");
        if (inputs.length() != 1) throw new IOException("重分组探针仅接受单个已完成文件结果");
        JSONObject original = inputs.getJSONObject(0);
        if (!"completed".equals(original.optString("status")) || original.getInt("sample_rate") != 16000
                || original.getLong("duration_ms") != 1200000)
            throw new IOException("输入不是已完成的16kHz、1200秒正式识别结果");
        String file = original.getString("file");
        JSONObject revised = new JSONObject(original.toString());
        String modelName = original.getJSONObject("diarization").getString("speaker_model");
        String speakerKey = null;
        for (String key : new String[]{SpeakerModels.CAM, SpeakerModels.ERES}) {
            if (key.equals(modelName) || SpeakerModels.INSTANCE.name(key).equals(modelName)) speakerKey = key;
        }
        if (speakerKey == null) throw new IOException("不支持的原始声纹模型：" + modelName);
        SharedPreferences prefs = target.getSharedPreferences("hotword_test", 0);
        SpeakerDecisionConfig config = new SpeakerDecisionConfig(
                fraction(prefs,"speaker_match_threshold",SpeakerDefaults.MATCH_PERCENT),
                SpeakerDefaults.WEAK_MATCH_PERCENT / 100.0,
                fraction(prefs,"speaker_new_max_similarity",SpeakerDefaults.NEW_MAX_PERCENT),
                fraction(prefs,"speaker_min_margin",SpeakerDefaults.MARGIN_PERCENT));
        state.put("source_file",file).put("speaker_key",speakerKey)
                .put("decision_config",new JSONObject().put("strong_match",config.getStrongMatch())
                        .put("weak_match",config.getWeakMatch()).put("new_speaker_max_similarity",config.getNewSpeakerMax())
                        .put("ambiguity_margin",config.getAmbiguityMargin()));
        checkpoint(out,state,"read_regroup_wav");
        long started = SystemClock.elapsedRealtime();
        float[] samples;
        try (FileInputStream stream = new FileInputStream(new File(dir,wavName))) {
            samples = WavReader.INSTANCE.read16k(stream);
        }
        state.put("wav_read_ms",SystemClock.elapsedRealtime()-started).put("input_samples",samples.length);
        if (samples.length != 19200000 || samples.length != original.getLong("input_samples"))
            throw new IOException("WAV采样数与正式识别结果不符");
        TranscriptReview review = TranscriptReview.INSTANCE;
        List<TranscriptReview.Row> before = review.fromResult(original,file);
        String originalText = review.render(before);
        state.put("original_result", original).put("original_transcript_text", originalText)
                .put("original_stored_render_matches",original.isNull("transcript_text") ? JSONObject.NULL :
                        originalText.equals(original.getString("transcript_text")));
        checkpoint(out,state,"offline_voice_recluster");
        started = SystemClock.elapsedRealtime();
        JSONObject regroup = OfflineVoiceRecluster.INSTANCE.apply(target.getAssets(), samples, revised, speakerKey,
                config, () -> Thread.currentThread().isInterrupted());
        state.put("regroup_ms", SystemClock.elapsedRealtime()-started).put("regroup",regroup)
                .put("regroup_status",regroup.getString("status")).put("regroup_reason",regroup.optString("reason",""));
        checkpoint(out,state,"verify_regroup");
        int turns = verifyRegroupBoundaries(original,revised);
        List<TranscriptReview.Row> after = review.fromResult(revised,file);
        if (before.size()!=after.size()) throw new IOException("重分组改变了可见转写行数");
        JSONArray changes = new JSONArray();
        for (int i=0; i<before.size(); i++) {
            TranscriptReview.Row a=before.get(i), b=after.get(i);
            if (!a.getSource().equals(b.getSource()) || !a.getSpeech().equals(b.getSpeech()))
                throw new IOException("第"+(i+1)+"行的源文件或ASR文本发生变化");
            if (!a.getPrefix().equals(b.getPrefix())) changes.put(new JSONObject().put("row",i+1)
                    .put("before",a.getPrefix()).put("after",b.getPrefix()));
        }
        String revisedText=review.render(after);
        revised.put("transcript_text",revisedText);
        state.put("rows",before.size()).put("verified_source_turns",turns).put("source_boundaries_and_text_unchanged",true)
                .put("changed_display_rows",changes.length()).put("display_changes",changes)
                .put("transcript_text",revisedText).put("results",new JSONArray().put(revised));
    }

    @Override public void onStart() {
        Bundle result=new Bundle();
        JSONObject state=new JSONObject();
        File out=null;
        int code=Activity.RESULT_CANCELED;
        boolean live="true".equalsIgnoreCase(options.getString("liveDeepSeek"));
        try{
            Context target=getTargetContext();
            File dir=target.getExternalFilesDir(null);
            if(dir==null)throw new IOException("目标外部文件目录不可用");
            String regroup=options.getString("regroupOnly","false");
            check("true".equalsIgnoreCase(regroup) || "false".equalsIgnoreCase(regroup),"regroupOnly只接受true或false");
            boolean local=Boolean.parseBoolean(regroup);
            String settings=options.getString("deviceSettings","false");
            check("true".equalsIgnoreCase(settings) || "false".equalsIgnoreCase(settings),"deviceSettings只接受true或false");
            boolean device=Boolean.parseBoolean(settings);
            check(!(device && local || live && (device || local)),"不能同时运行两种设备检查");
            java.util.Set<String> allowed=live?java.util.Collections.singleton("liveDeepSeek"):
                    device?java.util.Collections.singleton("deviceSettings"):
                    local?new java.util.HashSet<>(Arrays.asList("regroupOnly","inputFixture","audioFixture")):
                    new java.util.HashSet<>(Arrays.asList("regroupOnly","mockPort"));
            check(allowed.containsAll(options.keySet()),"不能混用不同探针模式的参数");
            out=new File(dir,live?REAL_OUTPUT:device?SETTINGS_OUTPUT:local?REGROUP_OUTPUT:CLOUD_OUTPUT);
            if(live)liveDeepSeek(target,out,state);
            else if(device)deviceSettings(target,out,state);
            else if(local)regroupOnly(target,dir,out,state);
            else cloudOnly(target,out,state);
            state.put("status","completed").put("phase","completed");
            save(out,state);
            result.putString("stream","处理完成，结果在"+out+"\n");
            code=Activity.RESULT_OK;
        }catch(Throwable error){
            String detail=live?liveError(error):error.getMessage();
            try{
                state.put("status","failed").put("error",detail==null?error.getClass().getSimpleName():detail);
                if(out!=null)save(out,state);
            }catch(Exception ignored){}
            result.putString("stream",live?"测试失败："+detail+"\n":"测试失败："+error.getClass().getSimpleName()+" "+detail+"\n");
        }finally{finish(code,result);}
    }
}
