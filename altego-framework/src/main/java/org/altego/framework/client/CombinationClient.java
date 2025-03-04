package org.altego.framework.client;

import org.altego.framework.client.listener.AbstractListener;
import org.altego.framework.api.HttpClient;
import org.altego.framework.api.LangModel;
import org.altego.framework.api.request.DefaultRequest;
import org.altego.framework.api.request.Message;
import org.altego.framework.api.response.ChatResponse;
import org.altego.framework.api.response.ModelResponse;
import org.altego.framework.client.listener.ChatListener;
import org.altego.framework.service.ChatService;
import org.altegox.common.log.Log;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CombinationClient implements ChatService<ChatResponse> {
    protected final LangModel model;
    private final HttpClient reasonerClient;
    private final HttpClient generateClient;
    protected final AbstractListener<ChatResponse> listener = new ChatListener<>();

    public static CombinationClient create(LangModel langModel) {
        return new CombinationClient(langModel);
    }

    protected CombinationClient(LangModel model) {
        this.model = model;
        this.reasonerClient = new HttpClient(
                model.getReasonerModel().getBaseUrl(),
                model.getReasonerModel().getApiKey()
        );
        this.generateClient = new HttpClient(
                model.getGenerateModel().getBaseUrl(),
                model.getGenerateModel().getApiKey()
        );
    }

    @Override
    public String chat(String message) {
        DefaultRequest reasonerRequest = DefaultRequest.builder()
                .model(model.getReasonerModel().getModelName())
                .stream(true) // 必须设置为 true 才返回推理内容
                .messages(List.of(Message.user(message)))
                .build();

        String reasonerContent = Objects.requireNonNull(reasonerClient.post(reasonerRequest, ChatResponse.class)
                        .map(response -> Optional.ofNullable(response)
                                .map(ChatResponse::getChoices)
                                .filter(choices -> !choices.isEmpty())
                                .map(choices -> choices.getFirst().getDelta())
                                .map(ChatResponse.Delta::getReasoningContent)
                                .orElse("")
                        )
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .block())
                .toString();

        reasonerContent = "<think>" + reasonerContent + "</think>";
        Log.info("Reasoner response: {}", reasonerContent);

        // 构造生成模型请求
        DefaultRequest generateRequest = DefaultRequest.builder()
                .model(model.getGenerateModel().getModelName())
                .messages(List.of(Message.user(reasonerContent + "\n" + message)))
                .build();

        generateClient.postSync(generateRequest, ChatResponse.class, listener);

        return Optional.ofNullable(listener.onFinish())
                .map(ChatResponse::getChoices)
                .filter(choices -> !choices.isEmpty())
                .map(choices -> choices.getFirst().getMessage().getContent())
                .orElse("");
    }


    @Override
    public ModelResponse<ChatResponse> chat(List<Message> messages) {
        boolean isStream = model.isStream();

        // Reasoner 请求，永远以流式拿到推理内容
        DefaultRequest reasonerRequest = DefaultRequest.builder()
                .model(model.getReasonerModel().getModelName())
                .stream(true)
                .messages(messages)
                .build();

        StringBuilder reasoningContentBuffer = new StringBuilder();

        Flux<ChatResponse> reasonerStream = reasonerClient.post(reasonerRequest, ChatResponse.class)
                .takeWhile(response -> {
                    System.out.println(response);
                    String rc = Optional.ofNullable(response)
                            .map(ChatResponse::getChoices)
                            .filter(choices -> !choices.isEmpty())
                            .map(choices -> choices.getFirst().getDelta())
                            .map(ChatResponse.Delta::getReasoningContent)
                            .orElse(null);
                    if (rc == null && (response.getChoices().getFirst().getDelta().getContent() != null
                            || response.getChoices().getFirst().getMessage().getContent() != null)) {
                        return false;
                    } else {
                        reasoningContentBuffer.append(rc);
                        return true;
                    }
                });

        Flux<ChatResponse> generatorStream = Flux.defer(() -> {
            String originContent = messages.getLast().getContent();
            String newContent = "<think>" + reasoningContentBuffer + "</think>\n" + originContent;
            messages.getLast().setContent(newContent);
            DefaultRequest generateRequest = DefaultRequest.builder()
                    .model(model.getGenerateModel().getModelName())
                    .stream(isStream)
                    .messages(messages)
                    .build();

//            if (isStream) {
            // 暂时只支持 流式响应
            return generateClient.post(generateRequest, ChatResponse.class);
//            } else {
//                generateClient.postSync(generateRequest, ChatResponse.class, listener);
//                return Flux.just(listener.onFinish());
//            }
        });

        Flux<ChatResponse> responseStream = reasonerStream.concatWith(generatorStream);

        return ModelResponse.of(responseStream);
    }

    // todo: 适配
    @Override
    public <R extends DefaultRequest> ModelResponse<ChatResponse> chat(R request) {
        if (request == null) throw new IllegalArgumentException("request is null");

        if (model.isStream() || request.isStream()) {
            return ModelResponse.of(reasonerClient.post(request, ChatResponse.class));
        } else {
            reasonerClient.postSync(request, ChatResponse.class, listener);
            return ModelResponse.of(listener.onFinish());
        }
    }

}
