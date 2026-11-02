import org.hotwordtest.TranscriptReview;
import org.hotwordtest.TranscriptReview.Edited;
import org.hotwordtest.TranscriptReview.Row;
import org.hotwordtest.TranscriptReview.VoiceEvidence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;

public final class TranscriptReviewCloudTest {
    private static final TranscriptReview REVIEW = TranscriptReview.INSTANCE;

    private static void check(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
    }

    private static final Row FIRST = new Row("private-file.wav", "[00:07–00:18]说话人1：",
            "请部暑模型，测式指标。", new VoiceEvidence("private-voice-id", true, 99, 7000, 18000));
    private static final Row SECOND = new Row("private-file.wav", "[00:22–00:29]说话人2：",
            "调式组件，再腹核结果；预算3万元，不延期。");
    private static final List<Row> ROWS = Arrays.asList(FIRST, SECOND);

    private static void twoRealStagesWithIsolatedPayloads() {
        AtomicInteger calls = new AtomicInteger();
        TranscriptReview.Completion fixture = (system, material, cancelled) -> {
            JSONArray items = new JSONArray(material);
            check(items.length() == 2 && !material.contains("private-") && !material.contains("00:"),
                    "File name, voice metadata or time leaked into model input");
            JSONObject first = items.getJSONObject(0);
            int n = calls.incrementAndGet();
            check(first.getString("speaker").equals("说话人1"), "Speaker label changed");
            if (n == 1) {
                check(first.getInt("row")==1 && first.length()==3 && first.getString("text").equals(FIRST.getSpeech()), "correction payload");
                JSONArray edits = new JSONArray();
                for (Object[] edit : new Object[][]{{1,"部暑","部署"},{1,"测式","测试"},
                        {2,"调式","调试"},{2,"腹核","复核"},{2,"3万元","5万元"},{2,"不延期","要延期"}}) {
                    edits.put(new JSONObject().put("row",edit[0]).put("old",edit[1]).put("new",edit[2]));
                }
                return new JSONObject().put("edits",edits).put("complete",true).toString();
            }
            check(n==2 && !first.has("row") && first.length()==2 && first.getString("text").equals("请部署模型，测试指标。"), "summary must use the actual correction");
            return "两人讨论了模型部署、指标测试和组件调试，预算为3万元。";
        };
        Edited edited = REVIEW.correct(fixture, ROWS, ()->false, (i,total)->Unit.INSTANCE);
        check(edited.getWarning().contains("修改4处") && edited.getWarning().contains("2条"), "independent spelling edits / protected facts");
        Row a=edited.getRows().get(0), b=edited.getRows().get(1);
        check(b.getSpeech().equals("调试组件，再复核结果；预算3万元，不延期。"), "protected amount, negation and punctuation");
        check(a.getVoice()==FIRST.getVoice() && a.getPrefix().equals(FIRST.getPrefix()) && a.getSource().equals(FIRST.getSource()), "row identity");
        String summary=REVIEW.summarize(fixture,edited.getRows(),()->false,(label)->Unit.INSTANCE);
        check(calls.get()==2 && summary.startsWith("【private-file.wav】\n两人讨论") && REVIEW.render(ROWS).contains("部暑"), "distinct outputs");
    }

    private static void rejectsIncompleteAndAmbiguousEdits() {
        for(String invalid : new String[]{"{\"edits\":[]}", "{\"complete\":false,\"edits\":[]}",
                "{\"edits\":[],\"complete\":true}extra", "{\"complete\":true,\"edits\":[{\"row\":1}]}"}){
            try{
                REVIEW.correct((s,u,c)->invalid,ROWS,()->false,(i,total)->Unit.INSTANCE);
                throw new AssertionError("Incomplete correction accepted: "+invalid);
            }catch(IllegalStateException expected){}
        }
        List<Row> repeated=Arrays.asList(new Row("","", "部暑两次部暑。"), new Row("","", "部暑任务。"));
        String conflict="{\"complete\":true,\"edits\":[{\"row\":1,\"old\":\"部暑\",\"new\":\"部署\"},"+
              "{\"row\":2,\"old\":\"部暑\",\"new\":\"部署\"},{\"row\":2,\"old\":\"暑任\",\"new\":\"署任\"}]}";
        Edited kept=REVIEW.correct((s,u,c)->conflict,repeated,()->false,(i,total)->Unit.INSTANCE);
        check(kept.getRows().equals(repeated) && kept.getWarning().contains("修改0处"),"ambiguous and overlapping anchors");
    }

    private static void cancellationAndSummaryRetry() {
        AtomicInteger requests=new AtomicInteger();
        try{
            REVIEW.correct((s,u,c)->{requests.incrementAndGet();return "";}, ROWS,()->true,(i,total)->Unit.INSTANCE);
            throw new AssertionError("No request allowed after cancellation");
        }catch(CancellationException expected){}
        check(requests.get()==0,"Cancellation before dispatch");
        Edited previous=REVIEW.correct((s,u,c)->"{\"complete\":true,\"edits\":[]}",ROWS,()->false,(i,total)->Unit.INSTANCE);
        AtomicBoolean cancelled=new AtomicBoolean(false);
        try{
            REVIEW.summarize((s,u,c)->{cancelled.set(true);return "半途取消的回答";},previous.getRows(),cancelled::get,(label)->Unit.INSTANCE);
            throw new AssertionError("Reply accepted after cancellation");
        }catch(CancellationException expected){}
        String retry=REVIEW.summarize((s,u,c)->"两人讨论预算和设备。",previous.getRows(),()->false,(label)->Unit.INSTANCE);
        check(previous.getRows().equals(ROWS) && retry.contains("讨论预算"),"Retry summary using completed correction");
    }

    public static void main(String[] args) {
        twoRealStagesWithIsolatedPayloads();
        rejectsIncompleteAndAmbiguousEdits();
        cancellationAndSummaryRetry();
        System.out.println("Passed 3 cloud transcript cases.");
    }
}

