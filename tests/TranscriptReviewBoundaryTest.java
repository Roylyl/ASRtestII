import org.hotwordtest.TranscriptReview;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

public class TranscriptReviewBoundaryTest {
    private final TranscriptReview review = TranscriptReview.INSTANCE;
    private final Method sameFacts = method("sameFacts", String.class, String.class, Set.class);
    private final Method plausible = method("plausibleEdit", String.class, String.class);
    private int checked;

    private static Method method(String name, Class<?>... signature) {
        try {
            Method m = TranscriptReview.class.getDeclaredMethod(name, signature);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void edit(String original, String proposed, boolean expected) throws Exception {
        boolean accepted = (boolean) plausible.invoke(review, original, proposed) &&
            (boolean) sameFacts.invoke(review, original, proposed, Collections.emptySet());
        if (accepted != expected) throw new AssertionError(original + " → " + proposed + ": " + accepted);
        checked++;
    }

    private void run() throws Exception {
        edit("我不去上海去北京", "我去上海不去北京", false);
        edit("我不去上海去北京", "我不去上海，去北京。", true);
        edit("我不去上嗨开会", "我不去上海开会", true);
        edit("部暑模型", "部署模型", true);
        edit("这款设备重3公斤", "这款设备重3克", false);
        edit("这款设备重三公斤", "这款设备重三克", false);
        edit("这款设备重3 公斤", "这款设备重3 克", false);
        edit("这款设备重3.5公斤", "这款设备重3.5公斤。", true);
        System.out.println("Passed " + checked + " transcript boundary checks.");
    }

    public static void main(String[] args) throws Exception {
        new TranscriptReviewBoundaryTest().run();
    }
}
