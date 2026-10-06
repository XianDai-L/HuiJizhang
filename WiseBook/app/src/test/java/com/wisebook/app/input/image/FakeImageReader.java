package com.wisebook.app.input.image;

import com.wisebook.llm.ImageReader;
import com.wisebook.llm.LlmException;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的假转写器。
 *
 * <p>有了它，截图解析器就能在没有网络、没有 Key、没有 Android 的情况下被测——
 * 理由与文字入口用 {@code FakeLlmClient} 完全一样。
 * 它同时记下收到的指令，让"归一等确定性处理有没有真的作用在链路上"可以被断言。
 */
public final class FakeImageReader implements ImageReader {

    private final String transcript;
    private final LlmException failure;
    private final List<String> receivedInstructions = new ArrayList<>();

    private FakeImageReader(String transcript, LlmException failure) {
        this.transcript = transcript;
        this.failure = failure;
    }

    public static FakeImageReader returning(String transcript) {
        return new FakeImageReader(transcript, null);
    }

    public static FakeImageReader failing(LlmException exception) {
        return new FakeImageReader(null, exception);
    }

    /** 上一次转写被问到什么指令；没调用过时为 {@code null} */
    public String lastInstruction() {
        return receivedInstructions.isEmpty()
                ? null
                : receivedInstructions.get(receivedInstructions.size() - 1);
    }

    /** 假转写器会返回的文本 */
    public String transcript() {
        return transcript;
    }

    @Override
    public String readImage(byte[] imageBytes, String mimeType, String instruction)
            throws LlmException {
        receivedInstructions.add(instruction);
        if (failure != null) {
            throw failure;
        }
        return transcript;
    }
}
